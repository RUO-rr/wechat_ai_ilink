---
title: Spring Framework 参考文档：AOP 代理机制（JDK 动态代理与 CGLIB）
source: https://docs.spring.io/spring-framework/reference/core/aop/proxying.html
fetched: 2026-09-14
lang: en
---
# Proxying Mechanisms

Spring AOP uses either JDK dynamic proxies or CGLIB to create the proxy for a given
target object. JDK dynamic proxies are built into the JDK, whereas CGLIB is a common
open-source class definition library (repackaged into spring-core).

If the target object to be proxied implements at least one interface, a JDK dynamic
proxy is used, and all of the interfaces implemented by the target type are proxied.
If the target object does not implement any interfaces, a CGLIB proxy is created which
is a runtime-generated subclass of the target type.

If you want to force the use of CGLIB proxying (for example, to proxy every method
defined for the target object, not only those implemented by its interfaces),
you can do so. However, you should consider the following issues:

- final classes cannot be proxied, because they cannot be extended.
- final methods cannot be advised, because they cannot be overridden.
- private methods cannot be advised, because they cannot be overridden.
- Methods that are not visible – for example, package-private methods in a parent class
from a different package – cannot be advised because they are effectively private.
- The constructor of your proxied object will not be called twice, since the CGLIB proxy
instance is created through Objenesis. However, if your JVM does not allow for
constructor bypassing, you might see double invocations and corresponding debug log
entries from Spring’s AOP support.
- Your CGLIB proxy usage may face limitations with the Java Module System. As a typical
case, you cannot create a CGLIB proxy for a class from the java.lang package when
deploying on the module path. Such cases require a JVM bootstrap flag
--add-opens=java.base/java.lang=ALL-UNNAMED which is not available for modules.

## Forcing Specific AOP Proxy Types

To force the use of CGLIB proxies, set the value of the proxy-target-class attribute
of the <aop:config> element to true, as follows:

```xml
<aop:config proxy-target-class="true">
	<!-- other beans defined here... -->
</aop:config>
```

To force CGLIB proxying when you use the @AspectJ auto-proxy support, set the
proxy-target-class attribute of the <aop:aspectj-autoproxy> element to true,
as follows:

```xml
<aop:aspectj-autoproxy proxy-target-class="true"/>
```

> Multiple <aop:config/> sections are collapsed into a single unified auto-proxy creator
at runtime, which applies the strongest proxy settings that any of the
<aop:config/> sections (typically from different XML bean definition files) specified.
This also applies to the <tx:annotation-driven/> and <aop:aspectj-autoproxy/>
elements.

To be clear, using proxy-target-class="true" on <tx:annotation-driven/>,
<aop:aspectj-autoproxy/>, or <aop:config/> elements forces the use of CGLIB
proxies for all three of them.

@EnableAspectJAutoProxy, @EnableTransactionManagement and related configuration
annotations offer a corresponding proxyTargetClass attribute. These are collapsed
into a single unified auto-proxy creator too, effectively applying the strongest
proxy settings at runtime. As of 7.0, this applies to individual proxy processors
as well, for example @EnableAsync, consistently participating in unified global
default settings for all auto-proxying attempts in a given application.

The global default proxy type may differ between setups. While the core framework
suggests interface-based proxies by default, Spring Boot may - depending on
configuration properties - enable class-based proxies by default.

As of 7.0, forcing a specific proxy type for individual beans is possible through
the @Proxyable annotation on a given @Bean method or @Component class, with
@Proxyable(INTERFACES) or @Proxyable(TARGET_CLASS) overriding any globally
configured default. For very specific purposes, you may even specify the proxy
interface(s) to use through @Proxyable(interfaces=…​), limiting the exposure
to selected interfaces rather than all interfaces that the target bean implements.

## Understanding AOP Proxies

Spring AOP is proxy-based. It is vitally important that you grasp the semantics of
what that last statement actually means before you write your own aspects or use any of
the Spring AOP-based aspects supplied with the Spring Framework.

Consider first the scenario where you have a plain-vanilla, un-proxied object reference,
as the following code snippet shows:

- Java
- Kotlin

```java
public class SimplePojo implements Pojo {

public void foo() {
		// this next method invocation is a direct call on the 'this' reference
		this.bar();
	}

public void bar() {
		// some logic...
	}
}
```

```kotlin
class SimplePojo : Pojo {

fun foo() {
		// this next method invocation is a direct call on the 'this' reference
		this.bar()
	}

fun bar() {
		// some logic...
	}
}
```

If you invoke a method on an object reference, the method is invoked directly on
that object reference, as the following image and listing show:

- Java
- Kotlin

```java
public class Main {

public static void main(String[] args) {
		Pojo pojo = new SimplePojo();
		// this is a direct method call on the 'pojo' reference
		pojo.foo();
	}
}
```

```kotlin
fun main() {
	val pojo = SimplePojo()
	// this is a direct method call on the 'pojo' reference
	pojo.foo()
}
```

Things change slightly when the reference that client code has is a proxy. Consider the
following diagram and code snippet:

- Java
- Kotlin

```java
public class Main {

public static void main(String[] args) {
		ProxyFactory factory = new ProxyFactory(new SimplePojo());
		factory.addInterface(Pojo.class);
		factory.addAdvice(new RetryAdvice());

Pojo pojo = (Pojo) factory.getProxy();
		// this is a method call on the proxy!
		pojo.foo();
	}
}
```

```kotlin
fun main() {
	val factory = ProxyFactory(SimplePojo())
	factory.addInterface(Pojo::class.java)
	factory.addAdvice(RetryAdvice())

val pojo = factory.proxy as Pojo
	// this is a method call on the proxy!
	pojo.foo()
}

> （以上为原文节选，完整内容见 source 链接。）
