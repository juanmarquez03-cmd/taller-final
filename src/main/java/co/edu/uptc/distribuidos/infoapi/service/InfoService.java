package co.edu.uptc.distribuidos.infoapi.service;

import co.edu.uptc.distribuidos.infoapi.config.TallerProperties;
import co.edu.uptc.distribuidos.infoapi.model.InfoResponse;
import co.edu.uptc.distribuidos.infoapi.model.VersionResponse;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Arma las respuestas de /api/info y /api/version. No guarda estado: cada petición
 * lee los datos en ese momento, así cualquier pod puede responder y puede borrarse
 * sin perder nada.
 */
@Service
public class InfoService {

    private static final DateTimeFormatter FORMATO_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final TallerProperties taller;
    private final LectorRecursos lector;
    private final String version;

    public InfoService(TallerProperties taller, LectorRecursos lector, @Value("${app.version}") String version) {
        this.taller = taller;
        this.lector = lector;
        this.version = version;
    }

    public InfoResponse info() {
        return new InfoResponse(
                ZonedDateTime.now().format(FORMATO_HORA),
                taller.integrantes(),
                lector.nombreContenedor(),
                lector.almacenamientoMB(),
                lector.ramMB());
    }

    public VersionResponse version() {
        return new VersionResponse(version, lector.nombreContenedor());
    }
}
