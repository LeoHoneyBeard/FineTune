package com.finetune.desktop.supporttriage

object SupportTriagePrompts {
    const val monolithicSystem: String =
        "Ты классификатор обращений в поддержку. Верни только валидный JSON без markdown и пояснений."

    const val compactSystem: String =
        "Return only valid output in specified format. No explanations."

    fun monolithicUser(input: String): String = """
        Проанализируй обращение пользователя и верни результат строго в формате JSON.

        Допустимые значения:
        intent: REFUND | TECH | INFO | OTHER
        sentiment: POSITIVE | NEUTRAL | NEGATIVE
        urgency: LOW | MEDIUM | HIGH
        needs_human: boolean
        confidence: number от 0.0 до 1.0
        status: OK | UNSURE | FAIL

        Правила:
        - REFUND: деньги списались, возврат, оплата, подписка не появилась после оплаты, двойное списание.
        - TECH: ошибка, баг, не работает, зависает, не приходит код, проблема со входом.
        - INFO: вопрос о правилах, сроках, инструкции, "как сделать".
        - OTHER: неясный, шумный или неподходящий запрос.
        - Если есть деньги + проблема с доступом/подпиской, intent = REFUND.
        - Если текст непонятный или слишком шумный, status = UNSURE или FAIL.
        - Если уверенность ниже 0.75, status = UNSURE.
        - Если ответ невозможно определить, status = FAIL.

        Обращение:
        $input

        Ответь только JSON:
        {
          "intent": "...",
          "sentiment": "...",
          "urgency": "...",
          "needs_human": true,
          "confidence": 0.0,
          "status": "..."
        }
    """.trimIndent()

    fun stage1User(input: String): String = """
        Извлеки признаки из текста.

        Формат ответа (одна строка):
        pay:{0|1} tech:{0|1} info:{0|1} comp:{0|1} access:{0|1} refund:{0|1} emo:{POS|NEU|NEG} noise:{LOW|MED|HIGH}

        Где:
        pay = есть оплата/деньги
        tech = есть техническая проблема
        info = вопрос/информация
        comp = жалоба/недовольство
        access = проблема доступа/подписки
        refund = явный запрос возврата
        emo = эмоция
        noise = уровень шума

        Текст:
        $input

        Никаких пояснений. Только строка.
    """.trimIndent()

    fun stage2User(stage1Compact: String): String = """
        На основе признаков выбери результат.

        Вход:
        $stage1Compact

        Формат ответа (одна строка):
        intent:{REFUND|TECH|INFO|OTHER} urg:{LOW|MED|HIGH} human:{0|1} conf:{0.0-1.0} status:{OK|UNSURE|FAIL}

        Правила (коротко):
        - pay + access или refund -> REFUND
        - tech без pay -> TECH
        - info без жалобы -> INFO
        - иначе OTHER
        - HIGH если проблемы с оплатой или доступом
        - MED если tech
        - LOW если info
        - human=1 если REFUND или HIGH или status!=OK
        - conf < 0.75 -> status=UNSURE
        - непонятно -> FAIL

        Только строка.
    """.trimIndent()

    fun stage3User(stage1Compact: String, stage2Compact: String): String = """
        Собери финальный JSON из compact-результатов. Не классифицируй заново.

        Stage1:
        $stage1Compact

        Stage2:
        $stage2Compact

        Верни ровно один JSON object в одну строку:
        {"intent":"REFUND","sentiment":"NEGATIVE","urgency":"HIGH","needs_human":true,"confidence":0.92,"status":"OK"}

        Допустимые одиночные значения:
        intent: REFUND, TECH, INFO, OTHER
        sentiment: POSITIVE, NEUTRAL, NEGATIVE
        urgency: LOW, MEDIUM, HIGH
        status: OK, UNSURE, FAIL

        Правила:
        - intent бери из Stage2 intent. Если там несколько через |, выбери одно: REFUND > TECH > INFO > OTHER.
        - sentiment бери из Stage1 emo: POS=POSITIVE, NEU=NEUTRAL, NEG=NEGATIVE.
        - urgency бери из Stage2 urg: LOW=LOW, MED=MEDIUM, HIGH=HIGH.
        - needs_human: human:1=true, human:0=false.
        - confidence бери из Stage2 conf.
        - status бери из Stage2 status, но если conf < 0.75, status=UNSURE.
        - Никогда не возвращай placeholders вроде "REFUND|TECH|INFO|OTHER".
        - Никогда не используй символ | в JSON.
        - Никаких пояснений. Только JSON.
    """.trimIndent()
}
