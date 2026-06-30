package com.anterka.closeauthbackend.common.config;

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.lang.NonNull;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Enables binding {@code closeauth.keys.rsa-public-key} / {@code rsa-private-key}
 * (and any other RSA key properties) from a PEM source.
 *
 * <p>Each property value may be:
 * <ul>
 *   <li>a Spring resource location ({@code classpath:keys/public.pem}, {@code file:/etc/closeauth/private.pem}), or</li>
 *   <li>inline PEM text (e.g. the contents of a secret/env var).</li>
 * </ul>
 * Blank values are treated as "not configured" so the server can fall back to an
 * ephemeral keypair in development.
 */
@Component
public class RsaKeyPropertyConverters {

    private RsaKeyPropertyConverters() {
    }

    private static InputStream openPem(ResourceLoader resourceLoader, String source) throws IOException {
        String trimmed = source.trim();
        if (trimmed.startsWith("classpath:") || trimmed.startsWith("file:") || trimmed.startsWith("http")) {
            return resourceLoader.getResource(trimmed).getInputStream();
        }
        // Treat the value itself as inline PEM content.
        return new java.io.ByteArrayInputStream(trimmed.getBytes(StandardCharsets.UTF_8));
    }

    @Component
    @ConfigurationPropertiesBinding
    public static class RsaPublicKeyConverter implements Converter<String, RSAPublicKey> {

        private final ResourceLoader resourceLoader;

        public RsaPublicKeyConverter(ResourceLoader resourceLoader) {
            this.resourceLoader = resourceLoader;
        }

        @Override
        public RSAPublicKey convert(@NonNull String source) {
            if (source.isBlank()) {
                return null;
            }
            try (InputStream is = openPem(resourceLoader, source)) {
                return RsaKeyConverters.x509().convert(is);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read RSA public key from: " + source, e);
            }
        }
    }

    @Component
    @ConfigurationPropertiesBinding
    public static class RsaPrivateKeyConverter implements Converter<String, RSAPrivateKey> {

        private final ResourceLoader resourceLoader;

        public RsaPrivateKeyConverter(ResourceLoader resourceLoader) {
            this.resourceLoader = resourceLoader;
        }

        @Override
        public RSAPrivateKey convert(@NonNull String source) {
            if (source.isBlank()) {
                return null;
            }
            try (InputStream is = openPem(resourceLoader, source)) {
                return RsaKeyConverters.pkcs8().convert(is);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read RSA private key from: " + source, e);
            }
        }
    }
}

