package server

import (
	"net/http"

	"github.com/go-chi/chi/v5"
)

// ──────────────────────────────────────────────────────────────────────────────
// Client Configuration Proxy Handlers
//
// All handlers require an authenticated session (RequireAuth middleware).
// The user's access token is forwarded as X-User-Token so Spring's
// TwoLayerAuthenticationFilter can identify the calling admin user.
// ──────────────────────────────────────────────────────────────────────────────

// ── Application Roles ────────────────────────────────────────────────────────

func (s *Server) handleCreateRole(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	s.proxyToSpring(w, r, http.MethodPost, s.springConfig.ClientRolesURL(clientID), getUserToken(r))
}

func (s *Server) handleGetRoles(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	s.proxyToSpring(w, r, http.MethodGet, s.springConfig.ClientRolesURL(clientID), getUserToken(r))
}

func (s *Server) handleGetRole(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	roleID := chi.URLParam(r, "roleId")
	s.proxyToSpring(w, r, http.MethodGet, s.springConfig.ClientRoleURL(clientID, roleID), getUserToken(r))
}

func (s *Server) handleUpdateRole(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	roleID := chi.URLParam(r, "roleId")
	s.proxyToSpring(w, r, http.MethodPut, s.springConfig.ClientRoleURL(clientID, roleID), getUserToken(r))
}

func (s *Server) handleDeleteRole(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	roleID := chi.URLParam(r, "roleId")
	s.proxyToSpring(w, r, http.MethodDelete, s.springConfig.ClientRoleURL(clientID, roleID), getUserToken(r))
}

// ── Registration Config ──────────────────────────────────────────────────────

func (s *Server) handleGetRegistrationConfig(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	s.proxyToSpring(w, r, http.MethodGet, s.springConfig.ClientRegConfigURL(clientID), getUserToken(r))
}

func (s *Server) handleUpdateRegistrationConfig(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	s.proxyToSpring(w, r, http.MethodPut, s.springConfig.ClientRegConfigURL(clientID), getUserToken(r))
}

// ── Themes ───────────────────────────────────────────────────────────────────

func (s *Server) handleCreateTheme(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	s.proxyToSpring(w, r, http.MethodPost, s.springConfig.ClientThemesURL(clientID), getUserToken(r))
}

func (s *Server) handleGetThemes(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	s.proxyToSpring(w, r, http.MethodGet, s.springConfig.ClientThemesURL(clientID), getUserToken(r))
}

func (s *Server) handleGetTheme(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	themeID := chi.URLParam(r, "themeId")
	s.proxyToSpring(w, r, http.MethodGet, s.springConfig.ClientThemeURL(clientID, themeID), getUserToken(r))
}

func (s *Server) handleUpdateTheme(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	themeID := chi.URLParam(r, "themeId")
	s.proxyToSpring(w, r, http.MethodPut, s.springConfig.ClientThemeURL(clientID, themeID), getUserToken(r))
}

func (s *Server) handleDeleteTheme(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	themeID := chi.URLParam(r, "themeId")
	s.proxyToSpring(w, r, http.MethodDelete, s.springConfig.ClientThemeURL(clientID, themeID), getUserToken(r))
}

func (s *Server) handleGetActiveTheme(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	s.proxyToSpring(w, r, http.MethodGet, s.springConfig.ClientActiveThemeURL(clientID), getUserToken(r))
}

func (s *Server) handleActivateTheme(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	themeID := chi.URLParam(r, "themeId")
	s.proxyToSpring(w, r, http.MethodPatch, s.springConfig.ClientThemeActivateURL(clientID, themeID), getUserToken(r))
}

// ── Theme Configurations ─────────────────────────────────────────────────────

func (s *Server) handleCreateThemeConfig(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	themeID := chi.URLParam(r, "themeId")
	s.proxyToSpring(w, r, http.MethodPost, s.springConfig.ClientThemeConfigsURL(clientID, themeID), getUserToken(r))
}

func (s *Server) handleGetThemeConfigs(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	themeID := chi.URLParam(r, "themeId")
	s.proxyToSpring(w, r, http.MethodGet, s.springConfig.ClientThemeConfigsURL(clientID, themeID), getUserToken(r))
}

func (s *Server) handleGetThemeConfig(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	themeID := chi.URLParam(r, "themeId")
	configID := chi.URLParam(r, "configId")
	s.proxyToSpring(w, r, http.MethodGet, s.springConfig.ClientThemeConfigURL(clientID, themeID, configID), getUserToken(r))
}

func (s *Server) handleUpdateThemeConfig(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	themeID := chi.URLParam(r, "themeId")
	configID := chi.URLParam(r, "configId")
	s.proxyToSpring(w, r, http.MethodPut, s.springConfig.ClientThemeConfigURL(clientID, themeID, configID), getUserToken(r))
}

func (s *Server) handleDeleteThemeConfig(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	themeID := chi.URLParam(r, "themeId")
	configID := chi.URLParam(r, "configId")
	s.proxyToSpring(w, r, http.MethodDelete, s.springConfig.ClientThemeConfigURL(clientID, themeID, configID), getUserToken(r))
}

// ── Admin Approval (Pending Registrations) ───────────────────────────────────

func (s *Server) handleGetPendingRegistrations(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	s.proxyToSpring(w, r, http.MethodGet, s.springConfig.PendingRegistrationsURL(clientID), getUserToken(r))
}

func (s *Server) handleGetPendingRegistrationsCount(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	s.proxyToSpring(w, r, http.MethodGet, s.springConfig.PendingRegistrationsCountURL(clientID), getUserToken(r))
}

func (s *Server) handleApproveRegistration(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	email := chi.URLParam(r, "email")
	s.proxyToSpring(w, r, http.MethodPost, s.springConfig.ApproveRegistrationURL(clientID, email), getUserToken(r))
}

func (s *Server) handleRejectRegistration(w http.ResponseWriter, r *http.Request) {
	clientID := chi.URLParam(r, "clientId")
	email := chi.URLParam(r, "email")
	s.proxyToSpring(w, r, http.MethodPost, s.springConfig.RejectRegistrationURL(clientID, email), getUserToken(r))
}
