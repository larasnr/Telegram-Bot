package ar.edu.utn.dds.k3003.model;

import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@Component
@ConditionalOnProperty(prefix = "bot", name = "enabled", havingValue = "true")
public class BotRunner {
    @Value("${bot.token}") String token;
    @Value("${bot.username}") String username;
    @Value("${urls.agregador}") String agregador;
    @Value("${urls.fuentes}") String fuentes;
    @Value("${urls.pdi}") String pdi;
    @Value("${urls.solicitudes}") String solicitudes;

    public void start() throws Exception {
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        api.registerBot(new TelegramBot(token, username, agregador, fuentes, pdi, solicitudes));
    }
}
