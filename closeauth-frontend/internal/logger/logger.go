package logger

import (
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
)

// Init initializes the slog logger with clean, readable output.
// Call this before any other initialization in main().
func Init(level, format string) {
	var logLevel slog.Level

	switch strings.ToLower(level) {
	case "debug":
		logLevel = slog.LevelDebug
	case "info":
		logLevel = slog.LevelInfo
	case "warn", "warning":
		logLevel = slog.LevelWarn
	case "error":
		logLevel = slog.LevelError
	default:
		logLevel = slog.LevelInfo
		slog.Warn("Invalid log level, defaulting to INFO", "provided", level)
	}

	opts := &slog.HandlerOptions{
		Level:     logLevel,
		AddSource: true,
		ReplaceAttr: func(groups []string, a slog.Attr) slog.Attr {
			if a.Key == slog.TimeKey {
				return slog.String("time", a.Value.Time().Format("2006-01-02 15:04:05"))
			}
			// Format source to look like Java's package.ClassName style:
			// e.g. "internal/server/routes.go:42" or "server.HandleLogin"
			if a.Key == slog.SourceKey {
				if src, ok := a.Value.Any().(*slog.Source); ok {
					// Build a short caller string: "package/file.go:line :: FuncName"
					shortFile := shortenFilePath(src.File)
					funcName := shortFuncName(src.Function)
					caller := fmt.Sprintf("%s:%d :: %s", shortFile, src.Line, funcName)
					return slog.String("caller", caller)
				}
			}
			return a
		},
	}

	var handler slog.Handler
	if strings.ToLower(format) == "json" {
		handler = slog.NewJSONHandler(os.Stdout, opts)
	} else {
		handler = slog.NewTextHandler(os.Stdout, opts)
	}

	slog.SetDefault(slog.New(handler))
}

// shortenFilePath returns the last 2 path components (e.g. "server/routes.go")
// to mimic Java's package.ClassName style without cluttering the log.
func shortenFilePath(fullPath string) string {
	if fullPath == "" {
		return "unknown"
	}
	// Use filepath to get last 2 segments: "internal/server/routes.go" -> "server/routes.go"
	dir := filepath.Base(filepath.Dir(fullPath))
	file := filepath.Base(fullPath)
	if dir == "." || dir == "/" {
		return file
	}
	return dir + "/" + file
}

// shortFuncName extracts the short function name from a fully qualified name.
// e.g. "github.com/user/project/internal/server.(*Server).HandleLogin" -> "server.HandleLogin"
func shortFuncName(fullFunc string) string {
	if fullFunc == "" {
		return "unknown"
	}
	// Get everything after the last '/'
	if idx := strings.LastIndex(fullFunc, "/"); idx >= 0 {
		fullFunc = fullFunc[idx+1:]
	}
	// Remove pointer receiver notation: "server.(*Server).HandleLogin" -> "server.Server.HandleLogin"
	fullFunc = strings.ReplaceAll(fullFunc, "(*", "")
	fullFunc = strings.ReplaceAll(fullFunc, ")", "")
	return fullFunc
}
