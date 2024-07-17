package main.java.org.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CrptApi {

    // Поля класса CrptApi
    private final HttpClient httpClient; // HTTP клиент для отправки запросов
    private final Gson gson; // Объект Gson для сериализации/десериализации JSON
    private final Semaphore semaphore; // Семафор для ограничения количества одновременных запросов
    private final ScheduledExecutorService scheduler; // Планировщик для регулярного освобождения разрешений

    /**
     * Конструктор класса CrptApi.
     *
     * @param timeUnit    Единица времени для ограничения запросов (например, секунда, минута)
     * @param requestLimit Максимальное количество запросов в указанную timeUnit
     */
    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        // Инициализация HTTP клиента
        this.httpClient = HttpClient.newHttpClient();
        // Создание объекта Gson для работы с JSON
        this.gson = new GsonBuilder().setDateFormat("yyyy-MM-dd").create();
        // Инициализация семафора для ограничения количества запросов
        this.semaphore = new Semaphore(requestLimit);

        // Планировщик для регулярного освобождения разрешений в семафоре с фиксированным числом потоков (в данном случае одним потоком).
        this.scheduler = Executors.newScheduledThreadPool(1);
        // scheduleAtFixedRate используется для запуска задачи (this::releaseSemaphore в данном случае) с фиксированным интервалом
        // времени между завершением предыдущего выполнения задачи и началом следующего выполнения.
        scheduler.scheduleAtFixedRate(this::releaseSemaphore, 1, timeUnit.toMillis(1), TimeUnit.MILLISECONDS);
    }

    /**
     * Делаем отдельный метод освобождения ресурсов в семафоре, так как необходим runnable метод для планировщика задач,
     * также можно было использовать анонимный класс при вызове semaphore.release(), чтобы исключить создание еще одного метода.
     */
    private void releaseSemaphore() {
        // Освобождение разрешений в семафоре, равное количеству доступных разрешений
        semaphore.release();
    }

    /**
     * Метод для отправки запроса на создание документа для ввода в оборот товара.
     *
     * @param accessToken Токен доступа для авторизации
     * @param document    Документ, который нужно создать в формате Java объекта
     * @return Обновленный объект Document на основе ответа от сервера
     * @throws InterruptedException, IOException
     */
    public Document createDocument(String accessToken, Document document) throws InterruptedException, IOException {
        // Захватить разрешение в семафоре перед выполнением запроса
        semaphore.acquire();

        try {
            // Преобразовать Java объект document в JSON строку с помощью Gson
            String jsonBody = gson.toJson(document);

            // Создать HTTP запрос
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://ismp.crpt.ru/api/v3/lk/documents/create")) // Установить URI для запроса
                    .header("Content-Type", "application/json") // Установить заголовок Content-Type
                    .header("Authorization", "Bearer " + accessToken) // Установить заголовок авторизации с токеном доступа
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody)) // Установить метод запроса POST и тело запроса
                    .build();

            // Отправить запрос и получить ответ, HttpResponse.BodyHandlers.ofString(): Возвращает BodyHandler, который преобразует тело ответа в строку
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Вывод статуса и тела ответа (для целей отладки или логирования)
            System.out.println("HTTP status code: " + response.statusCode());
            System.out.println("Response body: " + response.body());

            // Обновить объект Document на основе ответа от сервера
            document = updateDocumentFromResponse(document, response.body());

            // Возврат обновленного объекта Document
            return document;

        } finally {
            // Всегда освободить разрешение в семафоре после выполнения запроса
            semaphore.release();
            // Всегда закрывать планировщик задач после выполнения запроса
            shutdown();
        }
    }

    /**
     * Метод для обновления объекта Document на основе ответа от сервера.
     *
     * @param document    Исходный объект Document
     * @param responseBody Тело ответа от сервера в формате JSON
     * @return Обновленный объект Document
     */
    private Document updateDocumentFromResponse(Document document, String responseBody) {
        // Парсинг JSON ответа
        JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();

        // Обновление полей документа на основе ответа
        String docId = jsonObject.has("doc_id") ? jsonObject.get("doc_id").getAsString() : document.doc_id;
        String docStatus = jsonObject.has("doc_status") ? jsonObject.get("doc_status").getAsString() : document.doc_status;
        String docType = jsonObject.has("doc_type") ? jsonObject.get("doc_type").getAsString() : document.doc_type;
        boolean importRequest = jsonObject.has("importRequest") ? jsonObject.get("importRequest").getAsBoolean() : document.importRequest;
        String ownerInn = jsonObject.has("owner_inn") ? jsonObject.get("owner_inn").getAsString() : document.owner_inn;
        String participantInn = jsonObject.has("participant_inn") ? jsonObject.get("participant_inn").getAsString() : document.participant_inn;
        String producerInn = jsonObject.has("producer_inn") ? jsonObject.get("producer_inn").getAsString() : document.producer_inn;
        String productionDate = jsonObject.has("production_date") ? jsonObject.get("production_date").getAsString() : document.production_date;
        String productionType = jsonObject.has("production_type") ? jsonObject.get("production_type").getAsString() : document.production_type;
        String regDate = jsonObject.has("reg_date") ? jsonObject.get("reg_date").getAsString() : document.reg_date;
        String regNumber = jsonObject.has("reg_number") ? jsonObject.get("reg_number").getAsString() : document.reg_number;
        // Обновление product в документе на основе ответа

        Product[] products = document.products;
        if (jsonObject.has("products")) {
            products = gson.fromJson(jsonObject.getAsJsonArray("products"), Product[].class);
        }

        // Создание и возврат обновленного объекта Document
        return new Document(document.description, docId, docStatus, docType, importRequest,
                ownerInn, participantInn, producerInn, productionDate, productionType, products, regDate, regNumber);
    }

    /**
     * Закрытие планировщика для освобождения ресурсов, делать после создания документа.
     */
    public void shutdown() {
        scheduler.shutdown();
    }

    // Внутренний статический класс, представляющий структуру документа
    public static class Document {
        // Поля документа
        private final Description description;
        private final String doc_id;
        private final String doc_status;
        private final String doc_type;
        private final boolean importRequest;
        private final String owner_inn;
        private final String participant_inn;
        private final String producer_inn;
        private final String production_date;
        private final String production_type;
        private final Product[] products;
        private final String reg_date;
        private final String reg_number;

        //переопределяем строковое представление, для вывода на консоль.
        @Override
        public String toString() {
            return "Document{" +
                    "description=" + description +
                    ", doc_id='" + doc_id + '\'' +
                    ", doc_status='" + doc_status + '\'' +
                    ", doc_type='" + doc_type + '\'' +
                    ", importRequest=" + importRequest +
                    ", owner_inn='" + owner_inn + '\'' +
                    ", participant_inn='" + participant_inn + '\'' +
                    ", producer_inn='" + producer_inn + '\'' +
                    ", production_date='" + production_date + '\'' +
                    ", production_type='" + production_type + '\'' +
                    ", products=" + Arrays.toString(products) +
                    ", reg_date='" + reg_date + '\'' +
                    ", reg_number='" + reg_number + '\'' +
                    '}';
        }

        // Конструктор документа для инициализации всех полей
        public Document(Description description, String doc_id, String doc_status, String doc_type, boolean importRequest,
                        String owner_inn, String participant_inn, String producer_inn, String production_date,
                        String production_type, Product[] products, String reg_date, String reg_number) {
            this.description = description;
            this.doc_id = doc_id;
            this.doc_status = doc_status;
            this.doc_type = doc_type;
            this.importRequest = importRequest;
            this.owner_inn = owner_inn;
            this.participant_inn = participant_inn;
            this.producer_inn = producer_inn;
            this.production_date = production_date;
            this.production_type = production_type;
            this.products = products;
            this.reg_date = reg_date;
            this.reg_number = reg_number;
        }

    }

    // Внутренний статический класс, представляющий структуру описания
    public static class Description {
        // Поле описания (например, ИНН участника)
        private final String participantInn;

        // Конструктор описания для инициализации поля
        public Description(String participantInn) {
            this.participantInn = participantInn;
        }
    }

    // Внутренний статический класс, представляющий структуру продукта
    public static class Product {
        // Поля продукта
        private final String certificate_document;
        private final String certificate_document_date;
        private final String certificate_document_number;
        private final String owner_inn;
        private final String producer_inn;
        private final String production_date;
        private final String tnved_code;
        private final String uit_code;
        private final String uitu_code;

        // Конструктор продукта для инициализации всех полей
        public Product(String certificate_document, String certificate_document_date, String certificate_document_number,
                       String owner_inn, String producer_inn, String production_date, String tnved_code, String uit_code,
                       String uitu_code) {
            this.certificate_document = certificate_document;
            this.certificate_document_date = certificate_document_date;
            this.certificate_document_number = certificate_document_number;
            this.owner_inn = owner_inn;
            this.producer_inn = producer_inn;
            this.production_date = production_date;
            this.tnved_code = tnved_code;
            this.uit_code = uit_code;
            this.uitu_code = uitu_code;
        }
        public static void main(String[] args) {
            CrptApi crptApi = new CrptApi(TimeUnit.SECONDS, 5);

            CrptApi.Document document = new CrptApi.Document(
                    new CrptApi.Description("123456789012"),
                    "12345",
                    "pending",
                    "LP_INTRODUCE_GOODS",
                    true,
                    "987654321098",
                    "111111111111",
                    "222222222222",
                    "2020-01-23",
                    "food",
                    new CrptApi.Product[] {
                            new CrptApi.Product("cert123", "2020-01-23", "001", "987654321098", "222222222222", "2020-01-23", "0101", "0202", "0303")
                    },
                    "2020-01-23",
                    "R1234567890"
            );

            try {
                CrptApi.Document updatedDocument = crptApi.createDocument("your_access_token_here", document);
                System.out.println("Updated Document: " + updatedDocument.toString());
            } catch (InterruptedException | IOException e) {
                e.printStackTrace();
            }
        }
    }
}
