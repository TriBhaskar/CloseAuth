package handler

import "closeauth-backend-for-frontend/internal/sas/client"

type ClientHandler struct {
	oauth2Client *client.OAuth2Client
}