# syntax=docker/dockerfile:1
ARG POSTGRES_SOURCE
FROM --platform=$TARGETPLATFORM ${POSTGRES_SOURCE}
