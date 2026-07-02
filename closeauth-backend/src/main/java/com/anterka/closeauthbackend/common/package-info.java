/**
 * Shared, cross-cutting infrastructure used by every domain module: configuration,
 * configuration properties, the platform security primitives, the error model, and
 * stateless utilities.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>{@code config} / {@code config.properties} — Spring configuration and
 *       type-safe property bindings (surviving: {@code RsaKeyPropertyConverters},
 *       {@code CloseAuthProperties}, {@code RedisProperties}).</li>
 *   <li>{@code security} — shared authentication tokens, context holders, filters
 *       supporting layered tenant isolation.</li>
 *   <li>{@code exception} — the platform exception hierarchy feeding the RFC 7807
 *       error model.</li>
 *   <li>{@code util} — stateless helpers (surviving: {@code ClientIpResolver}).</li>
 * </ul>
 *
 * <p>References: Sections 10.3, 11, and 13 of CLOSEAUTH_PRODUCT_VISION_V2.md.
 */
package com.anterka.closeauthbackend.common;
