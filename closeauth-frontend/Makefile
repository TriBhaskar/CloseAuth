# Simple Makefile for a Go project

# Build the application (frontend first, then Go binary)
all: build test

# Build Vue frontend → then Go backend (embed.FS requires dist/ at compile time)
build: build-frontend build-backend

build-frontend:
	@echo "Building Vue frontend..."
	@cd closeauth-web && npm ci && npm run build
	@echo "Copying dist to embed location..."
	@rm -rf internal/static/dist
	@cp -r closeauth-web/dist internal/static/dist
	@test -f internal/static/dist/index.html || (echo "ERROR: Vue build failed — dist/index.html missing" && exit 1)
	@echo "Frontend build complete ✓"

build-backend:
	@echo "Building Go backend..."
	@go build -o main cmd/api/main.go
	@echo "Backend build complete ✓"

# Run the full application (builds frontend + backend, then runs)
run: build
	@echo "Starting server..."
	@./main

# Development: run Go backend only (expects Vite dev server on 5173 for HMR)
# Usage: In terminal 1: make dev
#        In terminal 2: cd closeauth-web && npm run dev
dev:
	@echo "Starting Go backend in development mode..."
	@echo "NOTE: For full UI with HMR, also run 'cd closeauth-web && npm run dev' in another terminal"
	@echo "      Vite dev server → http://localhost:5173 (proxies /api to Go on 8080)"
	@echo "      Go backend      → http://localhost:8080 (serves embedded SPA fallback)"
	@echo ""
	@go run cmd/api/main.go

# Create DB container
docker-run:
	@docker compose up --build

# Shutdown DB container
docker-down:
	@docker compose down

# Test the application
test:
	@echo "Testing..."
	@go test ./... -v

# Clean build artifacts
clean:
	@echo "Cleaning..."
	@rm -f main
	@rm -rf closeauth-web/dist
	@rm -rf internal/static/dist

# Live Reload (development)
watch:
	@if command -v air > /dev/null 2>&1; then \
		air; \
	else \
		echo "Installing air..."; \
		go install github.com/air-verse/air@latest; \
		air; \
	fi

.PHONY: all build build-frontend build-backend run dev docker-run docker-down test clean watch
