package server

// TODO: Add tests for new route handlers once implementations are complete.
// Test scenarios:
// - GET /api/health returns 200 with health status
// - GET /api/csrf returns CSRF token
// - POST /api/admin/login without CSRF returns 403
// - POST /api/admin/login with invalid credentials returns 401
// - GET /api/admin/me without session returns 401
// - GET /closeauth/oauth2/authorize proxies to Spring and handles redirects
