package com.eshop.common;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping({"/hc", "/liveness"})
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
