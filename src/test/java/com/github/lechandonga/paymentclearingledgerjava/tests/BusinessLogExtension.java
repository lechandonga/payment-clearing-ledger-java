package com.github.lechandonga.paymentclearingledgerjava.tests;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在测试日志中打印业务板块、用例与结果，便于从构建日志直接看出覆盖范围。
 */
public class BusinessLogExtension implements BeforeAllCallback, AfterAllCallback, TestWatcher {

    private static final String SEPARATOR = "=".repeat(72);

    @Override
    public void beforeAll(ExtensionContext context) {
        String module = module(context);
        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("【业务板块】" + module);
        System.out.println(SEPARATOR);
        context.getStore(namespace(context)).put("pass", new AtomicInteger(0));
        context.getStore(namespace(context)).put("fail", new AtomicInteger(0));
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        counter(context, "pass").incrementAndGet();
        System.out.println("  [用例通过] " + caseName(context));
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        counter(context, "fail").incrementAndGet();
        System.out.println("  [用例失败] " + caseName(context) + " -> " + cause);
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        System.out.println("  [用例中止] " + caseName(context));
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional reason) {
        System.out.println("  [用例跳过] " + caseName(context) + " (" + reason + ")");
    }

    @Override
    public void afterAll(ExtensionContext context) {
        AtomicInteger pass = counter(context, "pass");
        AtomicInteger fail = counter(context, "fail");
        System.out.println("【" + module(context) + "】小计：通过 " + pass.get() + "，失败 " + fail.get());
        System.out.println(SEPARATOR);
    }

    private static String module(ExtensionContext context) {
        return context.getRequiredTestClass().getSimpleName();
    }

    private static String caseName(ExtensionContext context) {
        return context.getDisplayName();
    }

    private static ExtensionContext.Namespace namespace(ExtensionContext context) {
        return ExtensionContext.Namespace.create(BusinessLogExtension.class, context.getRequiredTestClass());
    }

    private static ExtensionContext.Store counters(ExtensionContext context) {
        return context.getStore(namespace(context));
    }

    private static AtomicInteger counter(ExtensionContext context, String name) {
        return context.getStore(namespace(context)).get(name, AtomicInteger.class);
    }

}
