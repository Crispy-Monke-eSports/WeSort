package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class PasswordHashFormatTest {
    @Test
    void formatValidationAndVerificationAgree() {
        String valid = Auth.hash("test-password-123", 10);
        assertTrue(Auth.wellFormed(valid));
        assertTrue(Auth.verify("test-password-123", valid));
        String[] fields = valid.split("\\$");
        for (String rounds : new String[] {"0", "-1", "10000001", "abc"}) {
            String bad = fields[0] + "$" + rounds + "$" + fields[2] + "$" + fields[3];
            assertFalse(Auth.wellFormed(bad));
            assertFalse(Auth.verify("test-password-123", bad));
        }
        for (String bad : new String[] {
                fields[0] + "$10$%%%$" + fields[3],
                fields[0] + "$10$AA$" + fields[3],
                fields[0] + "$10$" + fields[2] + "$AA",
                valid + "$",
                valid + " ",
                fields[0] + "$10$" + Base64.getEncoder().encodeToString(new byte[256]) + "$" + fields[3]
        }) {
            assertFalse(Auth.wellFormed(bad));
            assertFalse(Auth.verify("test-password-123", bad));
        }
    }

    @Test
    void dummyCredentialHasTheSameBoundedFormatAsRealCredentials() {
        assertTrue(Auth.wellFormed(Auth.dummyHash()));
        assertFalse(Auth.verify("a".repeat(Auth.MAX_LENGTH + 1), Auth.dummyHash()));
    }
}
