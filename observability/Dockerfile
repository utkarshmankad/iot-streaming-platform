FROM python:3.13-alpine@sha256:9ef1a599f863231c3d91e941389bfdc4a2d6070557243d54df1a4e57015b960e
# The receiver uses the standard library. Drop installer code and its embedded wheels.
RUN python -m pip uninstall --yes pip \
    && rm -rf /usr/local/lib/python3.13/ensurepip
WORKDIR /app
COPY observability/receiver.py /app/receiver.py
ENV PYTHONDONTWRITEBYTECODE=1
EXPOSE 8080
ENTRYPOINT ["python", "/app/receiver.py"]
CMD ["serve"]
