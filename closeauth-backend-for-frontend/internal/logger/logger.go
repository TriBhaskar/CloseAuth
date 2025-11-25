package logger

import (
	"log/slog"
	"os"
	"strings"
)

// Init initializes the slog logger with clean, readable output
func Init(level, format string) {
	var logLevel slog.Level
	
	// Parse log level from environment
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
	}

	var handler slog.Handler
	
	opts := &slog.HandlerOptions{
		Level: logLevel,
		// Remove source file paths for cleaner logs
		AddSource: false,
		// Custom format to make logs more readable
		ReplaceAttr: func(groups []string, a slog.Attr) slog.Attr {
			// Simplify time format
			if a.Key == slog.TimeKey {
				return slog.String("time", a.Value.Time().Format("2006-01-02 15:04:05"))
			}
			return a
		},
	}

	// Choose format
	if strings.ToLower(format) == "json" {
		handler = slog.NewJSONHandler(os.Stdout, opts)
	} else {
		handler = slog.NewTextHandler(os.Stdout, opts)
	}

	slog.SetDefault(slog.New(handler))
}
