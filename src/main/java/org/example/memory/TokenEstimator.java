package org.example.memory;

import org.springframework.stereotype.Component;

@Component
public class TokenEstimator {
    public int estimate(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        int ascii = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) < 128) {
                ascii++;
            }
        }
        int nonAscii = content.length() - ascii;
        return Math.max(1, nonAscii + (int) Math.ceil(ascii / 4.0));
    }
}
