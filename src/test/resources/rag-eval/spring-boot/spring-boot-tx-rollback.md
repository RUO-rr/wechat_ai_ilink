---
title: Spring Framework 参考文档：事务回滚规则
source: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html
fetched: 2026-09-14
lang: en
---
# Rolling Back a Declarative Transaction

The previous section outlined the basics of how to specify transactional settings for
classes, typically service layer classes, declaratively in your application. This section
describes how you can control the rollback of transactions in a simple, declarative
fashion in XML configuration. For details on controlling rollback semantics declaratively
with the @Transactional annotation, see
@Transactional Settings.

The recommended way to indicate to the Spring Framework’s transaction infrastructure
that a transaction’s work is to be rolled back is to throw an Exception from code that
is currently executing in the context of a transaction. The Spring Framework’s
transaction infrastructure code catches any unhandled Exception as it bubbles up
the call stack and makes a determination whether to mark the transaction for rollback.

In its default configuration, the Spring Framework’s transaction infrastructure code
marks a transaction for rollback only in the case of runtime, unchecked exceptions.
That is, when the thrown exception is an instance or subclass of RuntimeException.
(Error instances also, by default, result in a rollback).

The default configuration also provides support for Vavr’s Try method to trigger
transaction rollbacks when it returns a 'Failure'.
This allows you to handle functional-style errors using Try and have the transaction
automatically rolled back in case of a failure. For more information on Vavr’s Try,
refer to the official Vavr documentation.
Here’s an example of how to use Vavr’s Try with a transactional method:

- Java

```java
@Transactional
public Try<String> myTransactionalMethod() {
	// If myDataAccessOperation throws an exception, it will be caught by the
	// Try instance created with Try.of() and wrapped inside the Failure class
	// which can be checked using the isFailure() method on the Try instance.
	return Try.of(delegate::myDataAccessOperation);
}
```

As of Spring Framework 6.1, there is also special treatment of CompletableFuture
(and general Future) return values, triggering a rollback for such a handle if it
was exceptionally completed at the time of being returned from the original method.
This is intended for @Async methods where the actual method implementation may
need to comply with a CompletableFuture signature (auto-adapted to an actual
asynchronous handle for a call to the proxy by @Async processing at runtime),
preferring exposure in the returned handle rather than rethrowing an exception:

- Java

```java
@Transactional @Async
public CompletableFuture<String> myTransactionalMethod() {
	try {
		return CompletableFuture.completedFuture(delegate.myDataAccessOperation());
	}
	catch (DataAccessException ex) {
		return CompletableFuture.failedFuture(ex);
	}
}
```

Checked exceptions that are thrown from a transactional method do not result in a rollback
in the default configuration. You can configure exactly which Exception types mark a
transaction for rollback, including checked exceptions by specifying rollback rules.

> Rollback rules

Rollback rules determine if a transaction should be rolled back when a given exception is
thrown, and the rules are based on exception types or exception patterns.

Rollback rules may be configured in XML via the rollback-for and no-rollback-for
attributes, which allow rules to be defined as patterns. When using
@Transactional,
rollback rules may be configured via the rollbackFor/noRollbackFor and
rollbackForClassName/noRollbackForClassName attributes, which allow rules to be
defined based on exception types or patterns, respectively.

When a rollback rule is defined with an exception type – for example, via rollbackFor –
that type will be used to match against the type of a thrown exception. Specifically,
given a configured exception type C, a thrown exception of type T will be considered
a match against C if T is equal to C or a subclass of C. This provides type
safety and avoids any unintentional matches that may occur when using a pattern. For
example, a value of jakarta.servlet.ServletException.class will only match thrown
exceptions of type jakarta.servlet.ServletException and its subclasses.

When a rollback rule is defined with an exception pattern, the pattern can be a fully
qualified class name or a substring of a fully qualified class name for an exception type
(which must be a subclass of Throwable), with no wildcard support at present. For
example, a value of "jakarta.servlet.ServletException" or "ServletException" will
match jakarta.servlet.ServletException and its subclasses.

You must carefully consider how specific a pattern is and whether to include package
information (which isn’t mandatory). For example, "Exception" will match nearly
anything and will probably hide other rules. "java.lang.Exception" would be correct if
"Exception" were meant to define a rule for all checked exceptions. With more unique
exception names such as "BaseBusinessException" there is likely no need to use the

> （以上为原文节选，完整内容见 source 链接。）
