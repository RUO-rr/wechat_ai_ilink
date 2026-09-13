---
title: Spring Framework 参考文档：声明通知（@Before / @After / @Around）
source: https://docs.spring.io/spring-framework/reference/core/aop/ataspectj/advice.html
fetched: 2026-09-14
lang: en
---
# Declaring Advice

Advice is associated with a pointcut expression and runs before, after, or around method
executions matched by the pointcut. The pointcut expression may be either an inline
pointcut or a reference to a named pointcut.

## Before Advice

You can declare before advice in an aspect by using the @Before annotation.

The following example uses an inline pointcut expression.

- Java
- Kotlin

```java
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;

@Aspect
public class BeforeExample {

@Before("execution(* com.xyz.dao.*.*(..))")
	public void doAccessCheck() {
		// ...
	}
}
```

```kotlin
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Before

@Aspect
class BeforeExample {

@Before("execution(* com.xyz.dao.*.*(..))")
	fun doAccessCheck() {
		// ...
	}
}
```

If we use a named pointcut, we can rewrite the preceding example
as follows:

- Java
- Kotlin

```java
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;

@Aspect
public class BeforeExample {

@Before("com.xyz.CommonPointcuts.dataAccessOperation()")
	public void doAccessCheck() {
		// ...
	}
}
```

```kotlin
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Before

@Aspect
class BeforeExample {

@Before("com.xyz.CommonPointcuts.dataAccessOperation()")
	fun doAccessCheck() {
		// ...
	}
}
```

## After Returning Advice

After returning advice runs when a matched method execution returns normally.
You can declare it by using the @AfterReturning annotation.

- Java
- Kotlin

```java
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.AfterReturning;

@Aspect
public class AfterReturningExample {

@AfterReturning("execution(* com.xyz.dao.*.*(..))")
	public void doAccessCheck() {
		// ...
	}
}
```

```kotlin
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.AfterReturning

@Aspect
class AfterReturningExample {

@AfterReturning("execution(* com.xyz.dao.*.*(..))")
	fun doAccessCheck() {
		// ...
	}
}
```

> You can have multiple advice declarations (and other members as well),
all inside the same aspect. We show only a single advice declaration in these
examples to focus the effect of each one.

Sometimes, you need access in the advice body to the actual value that was returned.
You can use the form of @AfterReturning that binds the return value to get that
access, as the following example shows:

- Java
- Kotlin

```java
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.AfterReturning;

@Aspect
public class AfterReturningExample {

@AfterReturning(
		pointcut="execution(* com.xyz.dao.*.*(..))",
		returning="retVal")
	public void doAccessCheck(Object retVal) {
		// ...
	}
}
```

```kotlin
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.AfterReturning

@Aspect
class AfterReturningExample {

@AfterReturning(
		pointcut = "execution(* com.xyz.dao.*.*(..))",
		returning = "retVal")
	fun doAccessCheck(retVal: Any?) {
		// ...
	}
}
```

The name used in the returning attribute must correspond to the name of a parameter
in the advice method. When a method execution returns, the return value is passed to
the advice method as the corresponding argument value. A returning clause also
restricts matching to only those method executions that return a value of the
specified type (in this case, Object, which matches any return value).

Please note that it is not possible to return a totally different reference when
using after returning advice.

## After Throwing Advice

After throwing advice runs when a matched method execution exits by throwing an
exception. You can declare it by using the @AfterThrowing annotation, as the
following example shows:

- Java
- Kotlin

```java
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.AfterThrowing;

@Aspect
public class AfterThrowingExample {

@AfterThrowing("execution(* com.xyz.dao.*.*(..))")
	public void doRecoveryActions() {
		// ...
	}
}
```

```kotlin
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.AfterThrowing

@Aspect
class AfterThrowingExample {

@AfterThrowing("execution(* com.xyz.dao.*.*(..))")
	fun doRecoveryActions() {
		// ...
	}
}
```

Often, you want the advice to run only when exceptions of a given type are thrown,
and you also often need access to the thrown exception in the advice body. You can
use the throwing attribute to both restrict matching (if desired — use Throwable
as the exception type otherwise) and bind the thrown exception to an advice parameter.
The following example shows how to do so:

- Java
- Kotlin

```java
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.AfterThrowing;

@Aspect
public class AfterThrowingExample {

@AfterThrowing(
		pointcut="execution(* com.xyz.dao.*.*(..))",
		throwing="ex")
	public void doRecoveryActions(DataAccessException ex) {
		// ...
	}
}
```

```kotlin
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.AfterThrowing

@Aspect
class AfterThrowingExample {

@AfterThrowing(
		pointcut = "execution(* com.xyz.dao.*.*(..))",
		throwing = "ex")
	fun doRecoveryActions(ex: DataAccessException) {
		// ...
	}
}
```

The name used in the throwing attribute must correspond to the name of a parameter in
the advice method. When a method execution exits by throwing an exception, the exception
is passed to the advice method as the corresponding argument value. A throwing clause
also restricts matching to only those method executions that throw an exception of the
specified type (DataAccessException, in this case).

> Note that @AfterThrowing does not indicate a general exception handling callback.
Specifically, an @AfterThrowing advice method is only supposed to receive exceptions
from the join point (user-declared target method) itself but not from an accompanying

> （以上为原文节选，完整内容见 source 链接。）
