package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os/signal"
	"syscall"
	"time"

	server "closeauth-backend-for-frontend/internal"
)

func gracefulShutdown(apiServer *http.Server, appServer *server.Server, done chan bool) {
    // Create context that listens for the interrupt signal from the OS.
    ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
    defer stop()

    // Listen for the interrupt signal.
    <-ctx.Done()

    log.Println("Shutting down gracefully, press Ctrl+C again to force")

    // The context is used to inform the server it has 5 seconds to finish
    // the request it is currently handling
    shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
    defer cancel()

    // Shutdown HTTP server
    if err := apiServer.Shutdown(shutdownCtx); err != nil {
        log.Printf("HTTP server forced to shutdown with error: %v", err)
    }

    // Shutdown application resources (database, etc.)
    if err := appServer.Shutdown(shutdownCtx); err != nil {
        log.Printf("Application resources shutdown error: %v", err)
    }

    log.Println("Server exiting")

    // Notify the main goroutine that the shutdown is complete
    done <- true
}

func main() {
    // Initialize server with error handling
    httpServer, appServer, err := server.NewServer()
    if err != nil {
        log.Fatalf("Failed to initialize server: %v", err)
    }

    // Verify database health at startup
    if err := appServer.HealthCheck(); err != nil {
        log.Printf("Warning: Database health check failed at startup: %v", err)
    } else {
        log.Println("Database health check passed")
    }

    // Create a done channel to signal when the shutdown is complete
    done := make(chan bool, 1)

    // Run graceful shutdown in a separate goroutine
    go gracefulShutdown(httpServer, appServer, done)

    log.Println("Server is running and ready to accept requests")

    err = httpServer.ListenAndServe()
    if err != nil && err != http.ErrServerClosed {
        panic(fmt.Sprintf("http server error: %s", err))
    }

    // Wait for the graceful shutdown to complete
    <-done
    log.Println("Graceful shutdown complete.")
}