package io.quarkiverse.servlet.perf;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class GreetingService {

    public String greet() {
        return "Hello from CDI";
    }
}
