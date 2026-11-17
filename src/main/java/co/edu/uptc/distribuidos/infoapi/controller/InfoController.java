package co.edu.uptc.distribuidos.infoapi.controller;

import co.edu.uptc.distribuidos.infoapi.model.InfoResponse;
import co.edu.uptc.distribuidos.infoapi.model.VersionResponse;
import co.edu.uptc.distribuidos.infoapi.service.InfoService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de la API:
 * <ul>
 *   <li>{@code GET /api/info}: hora, integrantes, contenedor, almacenamiento y RAM (MB).</li>
 *   <li>{@code GET /api/version}: versión de la aplicación y contenedor que responde.</li>
 * </ul>
 */
@RestController
public class InfoController {

    private final InfoService infoService;

    public InfoController(InfoService infoService) {
        this.infoService = infoService;
    }

    @GetMapping(value = "/api/info", produces = MediaType.APPLICATION_JSON_VALUE)
    public InfoResponse info() {
        return infoService.info();
    }

    @GetMapping(value = "/api/version", produces = MediaType.APPLICATION_JSON_VALUE)
    public VersionResponse version() {
        return infoService.version();
    }
}
