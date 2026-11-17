package co.edu.uptc.distribuidos.infoapi.model;

/**
 * Respuesta de {@code GET /api/version}: qué versión de la aplicación responde y desde
 * qué contenedor. Sirve para ver en vivo el cambio de una versión a otra.
 *
 * @param version    versión de la aplicación (la define el tag de Git al construir la imagen)
 * @param contenedor nombre del contenedor que responde (en Kubernetes, el nombre del pod)
 */
public record VersionResponse(String version, String contenedor) {
}
