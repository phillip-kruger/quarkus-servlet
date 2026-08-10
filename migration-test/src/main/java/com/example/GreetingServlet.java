package com.example;

import java.io.IOException;

import jakarta.inject.Inject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet(urlPatterns = "/greet", name = "greetingServlet")
public class GreetingServlet extends HttpServlet {

    @Inject
    GreetingService service;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String name = req.getParameter("name");
        resp.setContentType("text/plain");
        resp.getWriter().write(service.greet(name != null ? name : "World"));
    }
}
