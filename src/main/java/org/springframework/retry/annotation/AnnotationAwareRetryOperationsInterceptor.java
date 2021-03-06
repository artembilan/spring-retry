/*
 * Copyright 2013-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.retry.annotation;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.IntroductionInterceptor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.backoff.ExponentialRandomBackOffPolicy;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.backoff.Sleeper;
import org.springframework.retry.backoff.UniformRandomBackOffPolicy;
import org.springframework.retry.interceptor.MethodArgumentsKeyGenerator;
import org.springframework.retry.interceptor.MethodInvocationRecoverer;
import org.springframework.retry.interceptor.NewMethodArgumentsIdentifier;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.retry.interceptor.StatefulRetryOperationsInterceptor;
import org.springframework.retry.policy.MapRetryContextCache;
import org.springframework.retry.policy.RetryContextCache;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.ReflectionUtils.MethodCallback;

/**
 * WrappeMethodInterceptorr interceptor that interprets the retry metadata on the method it is invoking and
 * delegates to an appropriate RetryOperationsInterceptor.
 * 
 * @author Dave Syer
 * @since 2.0
 *
 */
public class AnnotationAwareRetryOperationsInterceptor implements IntroductionInterceptor {

	private Map<Method, MethodInterceptor> delegates = new HashMap<Method, MethodInterceptor>();

	private RetryContextCache retryContextCache = new MapRetryContextCache();

	private MethodArgumentsKeyGenerator methodArgumentsKeyGenerator;

	private NewMethodArgumentsIdentifier newMethodArgumentsIdentifier;
	
	private Sleeper sleeper;
	
	/**
	 * @param sleeper the sleeper to set
	 */
	public void setSleeper(Sleeper sleeper) {
		this.sleeper = sleeper;
	}

	/**
	 * Public setter for the {@link RetryContextCache}.
	 * 
	 * @param retryContextCache the {@link RetryContextCache} to set.
	 */
	public void setRetryContextCache(RetryContextCache retryContextCache) {
		this.retryContextCache = retryContextCache;
	}

	/**
	 * @param methodArgumentsKeyGenerator
	 */
	public void setKeyGenerator(MethodArgumentsKeyGenerator methodArgumentsKeyGenerator) {
		this.methodArgumentsKeyGenerator = methodArgumentsKeyGenerator;
	}

	/**
	 * @param newMethodArgumentsIdentifier
	 */
	public void setNewItemIdentifier(
			NewMethodArgumentsIdentifier newMethodArgumentsIdentifier) {
		this.newMethodArgumentsIdentifier = newMethodArgumentsIdentifier;
	}

	public Object invoke(MethodInvocation invocation) throws Throwable {
		MethodInterceptor delegate = getDelegate(invocation.getThis(), invocation.getMethod());
		return delegate.invoke(invocation);
	}

	@Override
	public boolean implementsInterface(Class<?> intf) {
		return Retryable.class.isAssignableFrom(intf);
	}

	private MethodInterceptor getDelegate(Object target, Method method) {
		if (!delegates.containsKey(method)) {
			synchronized (delegates) {
				if (!delegates.containsKey(method)) {
					Retryable retryable = AnnotationUtils.findAnnotation(method,
							Retryable.class);
					if (retryable == null) {
						retryable = AnnotationUtils.findAnnotation(
								method.getDeclaringClass(), Retryable.class);
					}
					MethodInterceptor delegate;
					if (retryable.stateful()) {
						delegate = getStatefulInterceptor(target, method, retryable);
					} else {
						delegate = getStatelessInterceptor(target, method, retryable);
					}
					delegates.put(method, delegate);
				}
			}
		}
		return delegates.get(method);
	}

	private MethodInterceptor getStatelessInterceptor(Object target, Method method, Retryable retryable) {
		RetryOperationsInterceptor interceptor = new RetryOperationsInterceptor();
		RetryTemplate template = new RetryTemplate();
		template.setRetryPolicy(getRetryPolicy(retryable));
		template.setBackOffPolicy(getBackoffPolicy(retryable.backoff()));
		interceptor.setRetryOperations(template);
		interceptor.setRecoverer(getRecoverer(target, method));
		return interceptor;
	}

	private MethodInterceptor getStatefulInterceptor(Object target, Method method, Retryable retryable) {
		StatefulRetryOperationsInterceptor interceptor = new StatefulRetryOperationsInterceptor();
		if (methodArgumentsKeyGenerator != null) {
			interceptor.setKeyGenerator(methodArgumentsKeyGenerator);
		}
		if (newMethodArgumentsIdentifier != null) {
			interceptor.setNewItemIdentifier(newMethodArgumentsIdentifier);
		}
		RetryTemplate template = new RetryTemplate();
		template.setRetryContextCache(retryContextCache);
		template.setRetryPolicy(getRetryPolicy(retryable));
		template.setBackOffPolicy(getBackoffPolicy(retryable.backoff()));
		interceptor.setRetryOperations(template);
		interceptor.setRecoverer(getRecoverer(target, method));
		return interceptor;
	}

	private MethodInvocationRecoverer<?> getRecoverer(Object target, Method method) {
		if (target instanceof MethodInvocationRecoverer) {
			return (MethodInvocationRecoverer<?>) target;
		}
		final AtomicBoolean foundRecoverable = new AtomicBoolean(false);
		ReflectionUtils.doWithMethods(target.getClass(), new MethodCallback() {
			@Override
			public void doWith(Method method) throws IllegalArgumentException,
					IllegalAccessException {
				if (AnnotationUtils.findAnnotation(method, Recover.class)!=null) {
					foundRecoverable.set(true);
				}
			}
		});
		if (!foundRecoverable.get()) {
			return null;
		}
		return new RecoverAnnotationRecoveryHandler<Object>(target, method);
	}

	private RetryPolicy getRetryPolicy(Retryable retryable) {
		Class<? extends Throwable>[] includes = retryable.value();
		if (includes.length == 0) {
			includes = retryable.include();
		}
		Class<? extends Throwable>[] excludes = retryable.exclude();
		if (includes.length == 0 && excludes.length == 0) {
			SimpleRetryPolicy simple = new SimpleRetryPolicy();
			simple.setMaxAttempts(retryable.maxAttempts());
			return simple;
		}
		Map<Class<? extends Throwable>, Boolean> policyMap = new HashMap<Class<? extends Throwable>, Boolean>();
		for (Class<? extends Throwable> type : includes) {
			policyMap.put(type, true);
		}
		for (Class<? extends Throwable> type : excludes) {
			policyMap.put(type, false);
		}
		SimpleRetryPolicy simple = new SimpleRetryPolicy(retryable.maxAttempts(),
				policyMap, true);
		return simple;
	}

	private BackOffPolicy getBackoffPolicy(Backoff backoff) {
		long min = backoff.delay()==0 ? backoff.value() : backoff.delay();
		long max = backoff.maxDelay();
		if (backoff.multiplier()>0) {
			ExponentialBackOffPolicy policy = new ExponentialBackOffPolicy();
			if (backoff.random()) {
				policy = new ExponentialRandomBackOffPolicy();
			}
			policy.setInitialInterval(min);
			policy.setMultiplier(backoff.multiplier());
			policy.setMaxInterval(max>min ? max : ExponentialBackOffPolicy.DEFAULT_MAX_INTERVAL);
			if (sleeper!=null) {
				policy.setSleeper(sleeper);
			}
			return policy;
		}
		if (max>min) {
			UniformRandomBackOffPolicy policy = new UniformRandomBackOffPolicy();
			policy.setMinBackOffPeriod(min);
			policy.setMaxBackOffPeriod(max);
			if (sleeper!=null) {
				policy.setSleeper(sleeper);
			}
			return policy;
		}
		FixedBackOffPolicy policy = new FixedBackOffPolicy();
		policy.setBackOffPeriod(min);
		if (sleeper!=null) {
			policy.setSleeper(sleeper);
		}
		return policy;
	}

}
