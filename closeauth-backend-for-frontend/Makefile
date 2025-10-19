# Simple Makefile for a Go project

# Build the application
all: build test
templ-install:
	@powershell -ExecutionPolicy Bypass -Command "if (Get-Command templ -ErrorAction SilentlyContinue) { \
		; \
	} else { \
		Write-Output 'Installing templ...'; \
		go install github.com/a-h/templ/cmd/templ@latest; \
		if (-not (Get-Command templ -ErrorAction SilentlyContinue)) { \
			Write-Output 'templ installation failed. Exiting...'; \
			exit 1; \
		} else { \
			Write-Output 'templ installed successfully.'; \
		} \
	}"
tailwind-install:
	@if not exist node_modules powershell -ExecutionPolicy Bypass -Command "npm install"

build: tailwind-install templ-install
	@echo "Building..."
	@templ generate
	@npm run css:build
	@go build -o main.exe cmd/main.go

# Run the application
run:
	@go run cmd/main.go

# Test the application
test:
	@echo "Testing..."
	@go test ./... -v

# Clean the binary
clean:
	@echo "Cleaning..."
	@rm -f main

# Live Reload with Air
watch:
	@powershell -ExecutionPolicy Bypass -Command "if (Get-Command air -ErrorAction SilentlyContinue) { \
		air; \
		Write-Output 'Watching...'; \
	} else { \
		Write-Output 'Installing air...'; \
		go install github.com/air-verse/air@latest; \
		air; \
		Write-Output 'Watching...'; \
	}"

.PHONY: all build run test clean watch tailwind-install templ-install
