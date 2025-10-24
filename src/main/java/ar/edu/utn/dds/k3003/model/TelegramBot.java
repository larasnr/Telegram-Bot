// ar/edu/utn/dds/k3003/model/TelegramBot.java
package ar.edu.utn.dds.k3003.model;

import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.*;
import java.util.stream.Collectors;

public class TelegramBot extends TelegramLongPollingBot {

    private final String token;
    private final String username;

    private final WebClient agregador;
    private final WebClient fuentes;
    private final WebClient pdi;
    private final WebClient solicitudes;

    public TelegramBot(String token,
                       String username,
                       String agregadorUrl,
                       String fuentesUrl,
                       String pdiUrl,
                       String solicitudesUrl) {
        super(token);
        this.token = token;
        this.username = username;
        this.agregador   = WebClient.builder().baseUrl(trimSlash(agregadorUrl)).build();
        this.fuentes     = WebClient.builder().baseUrl(trimSlash(fuentesUrl)).build();
        this.pdi         = WebClient.builder().baseUrl(trimSlash(pdiUrl)).build();
        this.solicitudes = WebClient.builder().baseUrl(trimSlash(solicitudesUrl)).build();
    }

    @Override public String getBotUsername() { return username; }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.getMessage() == null || update.getMessage().getText() == null) return;

        long chatId = update.getMessage().getChatId();
        String txt  = update.getMessage().getText().trim();

        try {
            if (txt.startsWith("/start")) {
                reply(chatId, """
                ¡Hola! Comandos:
                /coleccion <nombre>
                /hecho <id>
                /agregar_hecho <coleccion> | <titulo> | <descripcion>
                /agregar_pdi <hechoId> | <urlImagen>
                /solicitar_borrado <hechoId> | <motivo>
                /cambiar_solicitud <solicitudId> | <estado>
                """);
                return;
            }

            if (txt.startsWith("/coleccion ")) {
                String nombre = txt.replaceFirst("/coleccion\\s+", "").trim();
                cmdListarHechosDeColeccion(chatId, nombre);
                return;
            }

            if (txt.startsWith("/hecho ")) {
                String id = txt.replaceFirst("/hecho\\s+", "").trim();
                cmdVerHecho(chatId, id);
                return;
            }

            if (txt.startsWith("/agregar_hecho ")) {
                String args = txt.replaceFirst("/agregar_hecho\\s+", "");
                List<String> parts = splitPipe(args, 3);
                cmdAgregarHecho(chatId, parts.get(0), parts.get(1), parts.get(2));
                return;
            }

            if (txt.startsWith("/agregar_pdi ")) {
                String args = txt.replaceFirst("/agregar_pdi\\s+", "");
                List<String> parts = splitPipe(args, 2);
                cmdAgregarPdi(chatId, parts.get(0), parts.get(1));
                return;
            }

            if (txt.startsWith("/solicitar_borrado ")) {
                String args = txt.replaceFirst("/solicitar_borrado\\s+", "");
                List<String> parts = splitPipe(args, 2);
                cmdSolicitarBorrado(chatId, parts.get(0), parts.get(1));
                return;
            }

            if (txt.startsWith("/cambiar_solicitud ")) {
                String args = txt.replaceFirst("/cambiar_solicitud\\s+", "");
                List<String> parts = splitPipe(args, 2);
                cmdCambiarSolicitud(chatId, parts.get(0), parts.get(1));
                return;
            }

            reply(chatId, "No entendí el comando. Probá /start");

        } catch (WebClientResponseException ex) {
            reply(chatId, "HTTP " + ex.getRawStatusCode() + " → " + ex.getResponseBodyAsString());
        } catch (IllegalArgumentException ex) {
            reply(chatId, "Uso inválido: " + ex.getMessage());
        } catch (Exception ex) {
            reply(chatId, "Error: " + ex.getMessage());
        }
    }

    // ============= COMANDOS =============

    // 1) Listar hechos de una colección (Agregador)
    private void cmdListarHechosDeColeccion(long chatId, String coleccion) {
        if (coleccion.isBlank()) throw new IllegalArgumentException("falta <nombre>");
        List<Map> hechos = agregador.get()
                .uri("/colecciones/{nombre}/hechos", coleccion)
                .retrieve()
                .bodyToFlux(Map.class)
                .collectList()
                .block();

        if (hechos == null || hechos.isEmpty()) {
            reply(chatId, "Colección *" + coleccion + "* sin hechos.");
            return;
        }

        String listado = hechos.stream()
                .limit(10)
                .map(h -> "- " + val(h, "id") + " · " + val(h, "titulo"))
                .collect(Collectors.joining("\n"));

        reply(chatId, "*Hechos en* " + coleccion + ":\n" + listado);
    }

    // 2) Ver detalle de un hecho (Agregador y PDI)
    private void cmdVerHecho(long chatId, String hechoId) throws Exception {
        if (hechoId.isBlank()) throw new IllegalArgumentException("falta <id>");

        Map h = agregador.get()
                .uri("/hechos/{id}", hechoId)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (h == null) {
            reply(chatId, "No encontré el hecho " + hechoId);
            return;
        }

        String titulo = val(h, "titulo");
        String estado = nullTo(val(h, "estado"), "activo");
        String desc   = nullTo(val(h, "descripcion"), "-");
        reply(chatId, "*" + titulo + "*\nEstado: " + estado + "\n" + desc);

        // Si el agregador no devuelve PDIs, consultá al servicio PDI:
        List<Map> pdis = pdi.get()
                .uri(uriBuilder -> uriBuilder.path("/pdi").queryParam("hechoId", hechoId).build())
                .retrieve()
                .bodyToFlux(Map.class)
                .collectList()
                .block();

        if (pdis != null && !pdis.isEmpty()) {
            reply(chatId, "PDIs: " + pdis.size());
            for (Map p : pdis) {
                String contenido = val(p, "contenido");
                if (contenido != null && looksLikeUrl(contenido)) {
                    sendPhoto(chatId, contenido, "PDI");
                }
            }
        }
    }

    // 3) Agregar hecho (Fuentes)
    private void cmdAgregarHecho(long chatId, String coleccion, String titulo, String descripcion) {
        if (coleccion.isBlank() || titulo.isBlank()) {
            throw new IllegalArgumentException("<coleccion> | <titulo> | <descripcion>");
        }

        Map<String, Object> body = new HashMap<>();
        body.put("nombre_coleccion", coleccion);
        body.put("titulo", titulo);
        if (!descripcion.isBlank()) body.put("descripcion", descripcion);

        Map res = fuentes.post()
                .uri("/hechos")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        reply(chatId, "Hecho creado: " + (res != null ? res.get("id") : "(sin id)"));
    }

    // 4) Agregar PDI (Fuentes → FachadaPdI llama al servicio PDI real)
    private void cmdAgregarPdi(long chatId, String hechoId, String imageUrl) {
        if (hechoId.isBlank() || imageUrl.isBlank()) {
            throw new IllegalArgumentException("<hechoId> | <urlImagen>");
        }

        Map<String, Object> body = Map.of(
                "hechoId", hechoId,
                "contenido", imageUrl
        );

        Map res = fuentes.post()
                .uri("/pdis")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        reply(chatId, "PDI creado: " + (res != null ? res.get("id") : "(sin id)"));
    }

    // 5) Crear solicitud de borrado (Solicitudes)
    private void cmdSolicitarBorrado(long chatId, String hechoId, String motivo) {
        if (hechoId.isBlank() || motivo.isBlank()) {
            throw new IllegalArgumentException("<hechoId> | <motivo>");
        }

        Map<String, Object> body = Map.of(
                "hechoId", hechoId,
                "motivo", motivo,
                "estado", "pendiente"
        );

        Map res = solicitudes.post()
                .uri("/solicitudes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        reply(chatId, "Solicitud creada: " + (res != null ? res.get("id") : "(sin id)"));
    }

    // 6) Cambiar estado de solicitud (Solicitudes)
    private void cmdCambiarSolicitud(long chatId, String solicitudId, String estado) {
        if (solicitudId.isBlank() || estado.isBlank()) {
            throw new IllegalArgumentException("<solicitudId> | <estado>");
        }

        Map<String, Object> body = Map.of("estado", estado);

        solicitudes.patch()
                .uri("/solicitudes/{id}", solicitudId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        reply(chatId, "Solicitud " + solicitudId + " → estado=" + estado);
    }

    // ============= helpers =============
    private static String trimSlash(String base) {
        return (base != null && base.endsWith("/")) ? base.substring(0, base.length()-1) : base;
    }
    private static List<String> splitPipe(String args, int expected) {
        List<String> parts = Arrays.stream(args.split("\\|"))
                .map(String::trim).collect(Collectors.toList());
        if (parts.size() < expected)
            throw new IllegalArgumentException("se esperaban " + expected + " campos separados por '|'");
        return parts;
    }
    @SuppressWarnings("unchecked")
    private static String val(Map map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        return v == null ? null : String.valueOf(v);
    }
    private static String nullTo(String v, String def) { return (v == null || v.isBlank()) ? def : v; }
    private static boolean looksLikeUrl(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }
    private void reply(long chatId, String text) {
        try {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("Markdown")
                    .build());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    private void sendPhoto(long chatId, String url, String caption) {
        try {
            SendPhoto photo = new SendPhoto();
            photo.setChatId(chatId);
            photo.setPhoto(new org.telegram.telegrambots.meta.api.objects.InputFile(url));
            if (caption != null) photo.setCaption(caption);
            execute(photo);
        } catch (Exception e) {
            reply(chatId, "No pude enviar la imagen: " + e.getMessage());
        }
    }
}