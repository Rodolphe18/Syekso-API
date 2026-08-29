package dev.rodolphe.accesscontrol;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The entry point, and the replacement for Ktor's {@code Application.module()}.
 *
 * <p>{@code module()} assembled the application by hand: create the Mongo storage, create the
 * JwtService, create the SignalingHub, install the plugins, then hand all of it down to the routes.
 * Nothing of that kind appears here. {@code @SpringBootApplication} scans this package and every
 * package below it, builds the beans it finds, and wires them together.
 *
 * <p>The class must therefore stay at the root of the package tree: anything outside
 * {@code dev.rodolphe.accesscontrol} would simply never be discovered.
 */
@SpringBootApplication
public class AccessControlSpringApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccessControlSpringApplication.class, args);
    }
}
