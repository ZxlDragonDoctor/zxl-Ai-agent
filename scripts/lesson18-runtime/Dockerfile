FROM python:3.12-alpine
WORKDIR /worker
COPY --chmod=0444 excerpt.py /worker/excerpt.py
USER 65534:65534
ENTRYPOINT ["python", "-I", "-B", "/worker/excerpt.py"]
