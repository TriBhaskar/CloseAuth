package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	server "closeauth-backend-for-frontend/internal"
	"closeauth-backend-for-frontend/internal/logger"
)

func gracefulShutdown(apiServer *http.Server, appServer *server.Server, done chan bool) {
    // Create context that listens for the interrupt signal from the OS.
    ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
    defer stop()

    // Listen for the interrupt signal.
    <-ctx.Done()

    slog.Info("Shutting down gracefully (Ctrl+C again to force)")

    // The context is used to inform the server it has 5 seconds to finish
    // the request it is currently handling
    shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
    defer cancel()

    // Shutdown HTTP server
    if err := apiServer.Shutdown(shutdownCtx); err != nil {
        slog.Error("HTTP server shutdown error", "error", err)
    }

    // Shutdown application resources (database, etc.)
    if err := appServer.Shutdown(shutdownCtx); err != nil {
        slog.Error("Application shutdown error", "error", err)
    }

    slog.Info("Server stopped")

    // Notify the main goroutine that the shutdown is complete
    done <- true
}

func main() {
    // Initialize logger with configuration from environment
    logLevel := os.Getenv("LOG_LEVEL")
    if logLevel == "" {
        logLevel = "info" // Default to info level
    }

    logFormat := os.Getenv("LOG_FORMAT")
    if logFormat == "" {
        logFormat = "text" // Default to text format
    }

    logger.Init(logLevel, logFormat)

    slog.Info("CloseAuth BFF starting...")
    slog.Info("Log Level Info: " + logLevel)
    slog.Debug("Log Level Debug: " + logLevel)
    slog.Warn("Log Level Warn: " + logLevel)
    // Initialize server with error handling
    httpServer, appServer, err := server.NewServer()
    if err != nil {
        slog.Error("Failed to initialize server", "error", err)
        os.Exit(1)
    }

    // Verify database health at startup
    if err := appServer.HealthCheck(); err != nil {
        slog.Warn("Database health check failed", "error", err)
    } else {
        slog.Info("Database ready")
    }

    // Create a done channel to signal when the shutdown is complete
    done := make(chan bool, 1)

    // Run graceful shutdown in a separate goroutine
    go gracefulShutdown(httpServer, appServer, done)

    slog.Info("Server listening", "port", httpServer.Addr)

    err = httpServer.ListenAndServe()
    if err != nil && err != http.ErrServerClosed {
        slog.Error("Server error", "error", err)
        os.Exit(1)
    }

    // Wait for the graceful shutdown to complete
    <-done
    slog.Info("Shutdown complete")
}