# Vendored cua protocol

`cua/env/v1/*.proto` and `MEDIA.md` are copied unchanged from
[trycua/cua](https://github.com/trycua/cua) `libs/cua/proto`, commit
`ded0c1748619687613e19051ae60453bdbf700aa` (MIT, see `LICENSE-cua.md`).

They describe cua-spacesd, the daemon inside every cua Space (gRPC on TCP 3211). Wire generates
the message classes; the calls are made by `me.rerere.cuaspace.SpaceGrpc` over an SSH channel.
To update, copy the files from a newer commit and change the commit above.
