package server

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"strconv"
	"time"

	"closeauth-backend-for-frontend/internal/handlers"

	_ "github.com/joho/godotenv/autoload"
)

type Server struct {
	port          int
	authHandler   *handlers.AuthHandler
	clientHandler *handlers.ClientHandler
	publicHandler *handlers.PublicHandler
}

func NewServer() *http.Server {
	port, _ := strconv.Atoi(os.Getenv("PORT"))
	NewServer := &Server{
		port:          port,
		authHandler:   handlers.NewAuthHandler(),
		clientHandler: handlers.NewClientHandler(),
		publicHandler: handlers.NewPublicHandler(),
	}
	log.Printf("Starting server on port %d\n", NewServer.port)

	// Declare Server config
	server := &http.Server{
		Addr:         fmt.Sprintf(":%d", NewServer.port),
		Handler:      NewServer.RegisterRoutes(),
		IdleTimeout:  time.Minute,
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 30 * time.Second,
	}
	log.Printf("Server is running on port %d\n", NewServer.port)

	return server
}
