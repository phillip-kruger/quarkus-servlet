package io.quarkiverse.servlet.it;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class GreetingService {

    public String greet() {
        return "Hello from CDI";
    }
}
