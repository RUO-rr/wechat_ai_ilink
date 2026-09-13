---
title: Spring Framework 参考文档：声明切点（@Pointcut 与切点指示符）
source: https://docs.spring.io/spring-framework/reference/core/aop/ataspectj/pointcuts.html
fetched: 2026-09-14
lang: en
---
# Declaring a Pointcut

Pointcuts determine join points of interest and thus enable us to control
when advice runs. Spring AOP only supports method execution join points for Spring
beans, so you can think of a pointcut as matching the execution of methods on Spring
beans. A pointcut declaration has two parts: a signature comprising a name and any
parameters and a pointcut expression that determines exactly which method
executions we are interested in. In the @AspectJ annotation-style of AOP, a pointcut
signature is provided by a regular method definition, and the pointcut expression is
indicated by using the @Pointcut annotation (the method serving as the pointcut signature
must have a void return type).

An example may help make this distinction between a pointcut signature and a pointcut
expression clear. The following example defines a pointcut named anyOldTransfer that
matches the execution of any method named transfer:

- Java
- Kotlin

```java
@Pointcut("execution(* transfer(..))") // the pointcut expression
private void anyOldTransfer() {} // the pointcut signature
```

```kotlin
@Pointcut("execution(* transfer(..))") // the pointcut expression
private fun anyOldTransfer() {} // the pointcut signature
```

The pointcut expression that forms the value of the @Pointcut annotation is a regular
AspectJ pointcut expression. For a full discussion of AspectJ’s pointcut language, see
the AspectJ
Programming Guide (and, for extensions, the
AspectJ 5
Developer’s Notebook) or one of the books on AspectJ (such as Eclipse AspectJ, by Colyer
et al., or AspectJ in Action, by Ramnivas Laddad).

## Supported Pointcut Designators

Spring AOP supports the following AspectJ pointcut designators (PCD) for use in pointcut
expressions:

- execution: For matching method execution join points. This is the primary
pointcut designator to use when working with Spring AOP.
- within: Limits matching to join points within certain types (the execution
of a method declared within a matching type when using Spring AOP).
- this: Limits matching to join points (the execution of methods when using Spring
AOP) where the bean reference (Spring AOP proxy) is an instance of the given type.
- target: Limits matching to join points (the execution of methods when using
Spring AOP) where the target object (application object being proxied) is an instance
of the given type.
- args: Limits matching to join points (the execution of methods when using Spring
AOP) where the arguments are instances of the given types.
- @target: Limits matching to join points (the execution of methods when using
Spring AOP) where the class of the executing object has an annotation of the given type.
- @args: Limits matching to join points (the execution of methods when using Spring
AOP) where the runtime type of the actual arguments passed have annotations of the
given types.
- @within: Limits matching to join points within types that have the given
annotation (the execution of methods declared in types with the given annotation when
using Spring AOP).
- @annotation: Limits matching to join points where the subject of the join point
(the method being run in Spring AOP) has the given annotation.
Other pointcut types

The full AspectJ pointcut language supports additional pointcut designators that are not
supported in Spring: call, get, set, preinitialization,
staticinitialization, initialization, handler, adviceexecution, withincode, cflow,
cflowbelow, if, @this, and @withincode. Use of these pointcut designators in pointcut
expressions interpreted by Spring AOP results in an IllegalArgumentException being
thrown.

The set of pointcut designators supported by Spring AOP may be extended in future
releases to support more of the AspectJ pointcut designators.

Because Spring AOP limits matching to only method execution join points, the preceding discussion
of the pointcut designators gives a narrower definition than you can find in the
AspectJ programming guide. In addition, AspectJ itself has type-based semantics and, at
an execution join point, both this and target refer to the same object: the
object executing the method. Spring AOP is a proxy-based system and differentiates
between the proxy object itself (which is bound to this) and the target object behind the
proxy (which is bound to target).

> Due to the proxy-based nature of Spring’s AOP framework, calls within the target object
are, by definition, not intercepted. For JDK proxies, only public interface method
calls on the proxy can be intercepted. With CGLIB, public and protected method calls on
the proxy are intercepted (and even package-visible methods, if necessary). However,
common interactions through proxies should always be designed through public signatures.

Note that pointcut definitions are generally matched against any intercepted method.
If a pointcut is strictly meant to be public-only, even in a CGLIB proxy scenario with
potential non-public interactions through proxies, it needs to be defined accordingly.

If your interception needs to include method calls or even constructors within the target
class, consider the use of Spring-driven native AspectJ weaving instead
of Spring’s proxy-based AOP framework. This constitutes a different mode of AOP usage
with different characteristics, so be sure to make yourself familiar with weaving
before making a decision.

Spring AOP also supports an additional PCD named bean. This PCD lets you limit
the matching of join points to a particular named Spring bean or to a set of named
Spring beans (when using wildcards). The bean PCD has the following form:

```none
bean(idOrNameOfBean)

> （以上为原文节选，完整内容见 source 链接。）
