package com.github.gabert.deepflow.serializer;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

public class LoggingListener implements AgentBuilder.Listener {

    @Override
    public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
//        // Called when a class is discovered but not yet matched
//        System.out.println("Discovered: " + typeName);
    }

    @Override
    public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded, DynamicType dynamicType) {
        // Called when a class is transformed
//        System.out.println("\n>>> Transformed: " + typeDescription.getName());
    }

    @Override
    public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded) {
//        // Called when a class is ignored
//        System.out.println("Ignored: " + typeDescription.getName());
    }

    @Override
    public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
//        // Called when an error occurs
//        System.err.println("Error on class: " + typeName);
//        throwable.printStackTrace();
    }

    @Override
    public void onComplete(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
//        // Called when class instrumentation is complete
//        System.out.println("Completed: " + typeName);
    }
}
