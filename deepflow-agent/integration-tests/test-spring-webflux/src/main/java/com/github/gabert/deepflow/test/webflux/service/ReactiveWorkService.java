package com.github.gabert.deepflow.test.webflux.service;

import org.springframework.stereotype.Service;

@Service
public class ReactiveWorkService {

    public String blockingQuery(String input) {
        return "blocking:" + input;
    }

    public String transform(int value) {
        return "transformed:" + value;
    }
}
