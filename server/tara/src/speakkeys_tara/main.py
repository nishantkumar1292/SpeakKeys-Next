from .app import create_app


# Production configuration is validated when the ASGI process imports this module.
app = create_app()
