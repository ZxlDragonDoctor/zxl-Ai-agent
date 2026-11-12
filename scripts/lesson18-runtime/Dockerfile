FROM python:3.12-alpine@sha256:c5ba3deef00c7758561a0f03c26f44f10589263d40e732b8748e0d1f38f6dd89
WORKDIR /worker
COPY --chmod=0444 excerpt.py /worker/excerpt.py
USER 65534:65534
ENTRYPOINT ["python", "-I", "-B", "/worker/excerpt.py"]
