# MImic Listen Together server (Go, derived from BitChord's backend — GPL-3.0).
# Build:  docker compose -f server/party/docker-compose.yml up -d --build
FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN go vet ./... && CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o /out/mimic-party .

FROM alpine:3.22
RUN adduser -D -H -u 10001 party
COPY --from=build /out/mimic-party /usr/local/bin/mimic-party
USER party
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://127.0.0.1:8000/healthz || exit 1
ENTRYPOINT ["/usr/local/bin/mimic-party"]
