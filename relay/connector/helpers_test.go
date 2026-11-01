package main

import (
	"context"
	"errors"

	"github.com/gorilla/websocket"
)

func contextWithCancel() (context.Context, context.CancelFunc) {
	return context.WithCancel(context.Background())
}

func asCloseError(err error, target **websocket.CloseError) bool { return errors.As(err, target) }
