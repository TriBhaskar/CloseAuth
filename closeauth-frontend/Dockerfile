#==============================================================================
# CloseAuth Frontend - Production Multi-stage Docker Build
# Go BFF + Vue 3 SPA (embedded via embed.FS)
#==============================================================================

# Stage 1: Build Vue Frontend
FROM node:22-alpine AS frontend-build

WORKDIR /app/closeauth-web

COPY closeauth-web/ ./
RUN npm ci && npm run build

# Stage 2: Build Go binary
FROM golang:1.25.10-alpine3.23 AS backend-build

# Install CA certificates for go mod download over HTTPS
RUN apk add --no-cache ca-certificates tzdata

WORKDIR /app

COPY go.mod go.sum ./

# Print go.mod so we can confirm it copied correctly, then download
RUN cat go.mod && go mod download -x

COPY . .

# Copy Vue dist into embed location
COPY --from=frontend-build /app/closeauth-web/dist /app/internal/static/dist

RUN CGO_ENABLED=0 GOOS=linux go build -o closeauth-frontend cmd/api/main.go

# Stage 3: Production runtime
FROM alpine:3.21 AS prod

ARG APP_USER=closeauth
ARG APP_DIR=/home/$APP_USER
ARG UID=1000
ARG GID=1000

RUN addgroup --gid $GID $APP_USER && \
    adduser --uid $UID --disabled-password --no-create-home --shell /usr/sbin/nologin \
            $APP_USER --ingroup $APP_USER && \
    mkdir -p $APP_DIR && \
    chown -R $APP_USER:$APP_USER $APP_DIR

COPY --from=backend-build /app/closeauth-frontend $APP_DIR/closeauth-frontend
RUN chown $APP_USER:$APP_USER $APP_DIR/closeauth-frontend

USER $APP_USER
WORKDIR $APP_DIR

EXPOSE 8080

CMD ["./closeauth-frontend"]