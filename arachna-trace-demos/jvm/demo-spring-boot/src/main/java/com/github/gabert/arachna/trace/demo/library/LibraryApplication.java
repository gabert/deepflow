package com.github.gabert.arachna.trace.demo.library;

import com.github.gabert.arachna.trace.spi.session.spring.SessionIdFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class LibraryApplication {

    public static void main(String[] args) {
        SpringApplication.run(LibraryApplication.class, args);
    }

    /**
     * Register the SessionIdFilter from the SessionResolverSpring extension
     * JAR. Spring Boot picks up Filter @Bean instances and adds them to the
     * servlet chain automatically. Required for the agent's
     * `session_resolver=spring-session` config to work — without the filter
     * running per request, the resolver has nothing to read.
     */
    @Bean
    public SessionIdFilter sessionIdFilter() {
        return new SessionIdFilter();
    }
}
