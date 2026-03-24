package com.github.gabert.deepflow.demo;

public class MatcherMain {
    public static void main(String[] args) {
        methodA();
    }

    private static void methodA() {
        methodB();
    }

    private static void methodB() {
        methodC();
    }

    private static void methodC() {
        System.out.println("Hello Word!");
    }

}
