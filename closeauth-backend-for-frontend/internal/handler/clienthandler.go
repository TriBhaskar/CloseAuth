package handler

import "closeauth-backend-for-frontend/internal/client"

type ClientHandler struct {
	oauth2Client *client.OAuth2Client
}