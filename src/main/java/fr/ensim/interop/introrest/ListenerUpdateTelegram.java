package fr.ensim.interop.introrest;

import fr.ensim.interop.introrest.model.joke.Joke;
import fr.ensim.interop.introrest.model.telegram.ApiResponseUpdateTelegram;
import fr.ensim.interop.introrest.model.telegram.Message;
import fr.ensim.interop.introrest.model.telegram.Update;
import fr.ensim.interop.introrest.model.weather.ForecastResponse;
import fr.ensim.interop.introrest.model.weather.WeatherResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class ListenerUpdateTelegram implements CommandLineRunner {

	@Value("${telegram.api.url}")
	private String telegramApiUrl;

	@Value("${telegram.bot.id}")
	private String botToken;

	@Value("${open.weather.api.url}")
	private String weatherApiUrl;

	@Value("${open.weather.api.token}")
	private String weatherApiToken;

	private final RestTemplate restTemplate = new RestTemplate();
	private final Random random = new Random();
	private int offset = 0;

	private static final Logger logger = Logger.getLogger("ListenerUpdateTelegram");

	private static final List<Joke> JOKES = Arrays.asList(
		new Joke(1, "Le programmeur optimiste", "Un programmeur dit à sa femme : 'Va faire les courses. Prends un litre de lait, et si ils ont des oeufs, prends-en 6.' Elle revient avec 6 litres de lait. 'Ils avaient des oeufs.'", 8.5),
		new Joke(2, "L'ascenseur", "Pourquoi les plongeurs plongent-ils toujours en arrière et jamais en avant ? Parce que sinon ils tomberaient dans le bateau.", 7.0),
		new Joke(3, "Le crocodile", "Qu'est-ce qu'un crocodile qui surveille des valises ? Un sac à dents.", 6.5),
		new Joke(4, "La bibliothèque", "Un homme entre dans une bibliothèque et demande : 'Vous avez des livres sur la paranoïa ?' La bibliothécaire chuchote : 'Ils sont juste derrière vous !'", 9.0),
		new Joke(5, "Le menuisier", "Qu'est-ce qu'un canif ? Un petit fien.", 5.0)
	);

	@Override
	public void run(String... args) {
		logger.log(Level.INFO, "Démarage du listener d'updates Telegram...");

		Timer timer = new Timer();
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				try {
					pollUpdates();
				} catch (Exception e) {
					logger.log(Level.WARNING, "Erreur lors du polling : " + e.getMessage());
				}
			}
		}, 0, 3000);
	}

	private void pollUpdates() {
		URI uri = UriComponentsBuilder
				.fromHttpUrl(telegramApiUrl + "/bot" + botToken + "/getUpdates")
				.queryParam("offset", offset)
				.build()
				.toUri();

		ApiResponseUpdateTelegram response = restTemplate.getForObject(uri, ApiResponseUpdateTelegram.class);

		if (response == null || !response.getOk() || response.getResult() == null) return;

		for (Update update : response.getResult()) {
			offset = update.getUpdateId() + 1;
			if (update.hasMessage() && update.getMessage().hasText()) {
				handleMessage(update.getMessage());
			}
		}
	}

	private void handleMessage(Message message) {
		String text = message.getText().toLowerCase();
		String chatId = String.valueOf(message.getChatId());

		if (text.contains("meteo") || text.contains("météo")) {
			boolean forecast = text.contains("forecast");
			String city = extractCity(text.replace("forecast", "").trim());
			sendMessage(chatId, forecast ? getForecastText(city) : getWeatherText(city));
		} else if (text.contains("blague")) {
			Joke joke = JOKES.get(random.nextInt(JOKES.size()));
			sendMessage(chatId, joke.getTitre() + "\n\n" + joke.getTexte() + "\n\nNote : " + joke.getNote() + "/10");
		}
	}

	private String extractCity(String text) {
		String[] parts = text.split("meteo|météo");
		if (parts.length > 1 && !parts[1].trim().isEmpty()) {
			return parts[1].trim();
		}
		return "Le Mans";
	}

	private String getWeatherText(String city) {
		try {
			URI uri = UriComponentsBuilder.fromHttpUrl(weatherApiUrl + "/weather")
					.queryParam("q", city)
					.queryParam("appid", weatherApiToken)
					.queryParam("units", "metric")
					.queryParam("lang", "fr")
					.build()
					.toUri();

			WeatherResponse weather = restTemplate.getForObject(uri, WeatherResponse.class);

			if (weather != null && weather.getWeather() != null && !weather.getWeather().isEmpty()) {
				return String.format("Météo à %s : %s, %.1f°C",
						weather.getCity(),
						weather.getWeather().get(0).getDescription(),
						weather.getMain().getTemp());
			}
		} catch (Exception e) {
			return "Désolé, ville introuvable : " + city;
		}
		return "Météo indisponible.";
	}

	private String getForecastText(String city) {
		try {
			URI uri = UriComponentsBuilder.fromHttpUrl(weatherApiUrl + "/forecast")
					.queryParam("q", city)
					.queryParam("appid", weatherApiToken)
					.queryParam("units", "metric")
					.queryParam("lang", "fr")
					.build()
					.toUri();

			ForecastResponse forecast = restTemplate.getForObject(uri, ForecastResponse.class);

			if (forecast == null || forecast.getList() == null || forecast.getList().isEmpty()) {
				return "Prévisions indisponibles pour " + city;
			}

			StringBuilder sb = new StringBuilder("Prévisions pour " + city + " :\n");
			forecast.getList().stream()
					.filter(item -> item.getDateText() != null && item.getDateText().contains("12:00:00"))
					.limit(2)
					.forEach(item -> sb.append(String.format("- %s : %s, %.1f°C\n",
							item.getDateText().substring(0, 10),
							item.getWeather().get(0).getDescription(),
							item.getMain().getTemp())));

			return sb.toString().trim();
		} catch (Exception e) {
			return "Désolé, ville introuvable : " + city;
		}
	}

	private void sendMessage(String chatId, String text) {
		String url = telegramApiUrl + "/bot" + botToken + "/sendMessage";

		Map<String, String> body = new HashMap<>();
		body.put("chat_id", chatId);
		body.put("text", text);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

		restTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);
	}
}
