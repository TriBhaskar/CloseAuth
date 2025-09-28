# Project closeauth-backend-for-frontend

One Paragraph of project description goes here

## Getting Started

These instructions will get you a copy of the project up and running on your local machine for development and testing purposes. See deployment for notes on how to deploy the project on a live system.

## MakeFile

Run build make command with tests

```bash
make all
```

Build the application

```bash
make build
```

Run the application

```bash
make run
```

Live reload the application:

```bash
make watch
```

Run the test suite:

```bash
make test
```

Clean up binary from the last build:

```bash
make clean
```

In windows system, to make this project running

first install

go install github.com/a-h/templ/cmd/templ@latest

then download tailwindcss

if (!(Test-Path "tailwindcss.exe")) { Invoke-WebRequest -Uri "https://github.com/tailwindlabs/tailwindcss/releases/download/v3.4.10/tailwindcss-windows-x64.exe" -OutFile "tailwindcss.exe" }

then generate the templ templates

templ generate

build css

.\tailwindcss.exe -i cmd/web/styles/input.css -o cmd/web/assets/css/output.css

build go application

go build -o main.exe cmd/api/main.go

run the application

go run cmd/api/main.go
