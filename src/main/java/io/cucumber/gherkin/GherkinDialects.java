package io.cucumber.gherkin;

import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;

final class GherkinDialects {
    static final Map<String, GherkinDialect> DIALECTS;

    static {
        Map<String, GherkinDialect> dialects = new LinkedHashMap<>();

        dialects.put("af", new GherkinDialect(
            "af",
            "Afrikaans",
            "Afrikaans",
            unmodifiableList(asList("Funksie", "Besigheid Behoefte", "Vermoë")),
            unmodifiableList(asList("Regel")),
            unmodifiableList(asList("Voorbeeld", "Situasie")),
            unmodifiableList(asList("Situasie Uiteensetting")),
            unmodifiableList(asList("Agtergrond")),
            unmodifiableList(asList("Voorbeelde")),
            unmodifiableList(asList("* ", "Gegewe ")),
            unmodifiableList(asList("* ", "Wanneer ")),
            unmodifiableList(asList("* ", "Dan ")),
            unmodifiableList(asList("* ", "En ")),
            unmodifiableList(asList("* ", "Maar "))
        ));

        dialects.put("am", new GherkinDialect(
            "am",
            "Armenian",
            "հայերեն",
            unmodifiableList(asList("Ֆունկցիոնալություն", "Հատկություն")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Օրինակ", "Սցենար")),
            unmodifiableList(asList("Սցենարի կառուցվացքը")),
            unmodifiableList(asList("Կոնտեքստ")),
            unmodifiableList(asList("Օրինակներ")),
            unmodifiableList(asList("* ", "Դիցուք ")),
            unmodifiableList(asList("* ", "Եթե ", "Երբ ")),
            unmodifiableList(asList("* ", "Ապա ")),
            unmodifiableList(asList("* ", "Եվ ")),
            unmodifiableList(asList("* ", "Բայց "))
        ));

        dialects.put("amh", new GherkinDialect(
            "amh",
            "Amharic",
            "አማርኛ",
            unmodifiableList(asList("ስራ", "የተፈለገው ስራ", "የሚፈለገው ድርጊት")),
            unmodifiableList(asList("ህግ")),
            unmodifiableList(asList("ምሳሌ", "ሁናቴ")),
            unmodifiableList(asList("ሁናቴ ዝርዝር", "ሁናቴ አብነት")),
            unmodifiableList(asList("ቅድመ ሁኔታ", "መነሻ", "መነሻ ሀሳብ")),
            unmodifiableList(asList("ምሳሌዎች", "ሁናቴዎች")),
            unmodifiableList(asList("* ", "የተሰጠ ")),
            unmodifiableList(asList("* ", "መቼ ")),
            unmodifiableList(asList("* ", "ከዚያ ")),
            unmodifiableList(asList("* ", "እና ")),
            unmodifiableList(asList("* ", "ግን "))
        ));

        dialects.put("an", new GherkinDialect(
            "an",
            "Aragonese",
            "Aragonés",
            unmodifiableList(asList("Caracteristica")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Eixemplo", "Caso")),
            unmodifiableList(asList("Esquema del caso")),
            unmodifiableList(asList("Antecedents")),
            unmodifiableList(asList("Eixemplos")),
            unmodifiableList(asList("* ", "Dau ", "Dada ", "Daus ", "Dadas ")),
            unmodifiableList(asList("* ", "Cuan ")),
            unmodifiableList(asList("* ", "Alavez ", "Allora ", "Antonces ")),
            unmodifiableList(asList("* ", "Y ", "E ")),
            unmodifiableList(asList("* ", "Pero "))
        ));

        dialects.put("ar", new GherkinDialect(
            "ar",
            "Arabic",
            "العربية",
            unmodifiableList(asList("خاصية")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("مثال", "سيناريو")),
            unmodifiableList(asList("سيناريو مخطط")),
            unmodifiableList(asList("الخلفية")),
            unmodifiableList(asList("امثلة")),
            unmodifiableList(asList("* ", "بفرض ")),
            unmodifiableList(asList("* ", "متى ", "عندما ")),
            unmodifiableList(asList("* ", "اذاً ", "ثم ")),
            unmodifiableList(asList("* ", "و ")),
            unmodifiableList(asList("* ", "لكن "))
        ));

        dialects.put("ast", new GherkinDialect(
            "ast",
            "Asturian",
            "asturianu",
            unmodifiableList(asList("Carauterística")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Exemplo", "Casu")),
            unmodifiableList(asList("Esbozu del casu")),
            unmodifiableList(asList("Antecedentes")),
            unmodifiableList(asList("Exemplos")),
            unmodifiableList(asList("* ", "Dáu ", "Dada ", "Daos ", "Daes ")),
            unmodifiableList(asList("* ", "Cuando ")),
            unmodifiableList(asList("* ", "Entós ")),
            unmodifiableList(asList("* ", "Y ", "Ya ")),
            unmodifiableList(asList("* ", "Peru "))
        ));

        dialects.put("az", new GherkinDialect(
            "az",
            "Azerbaijani",
            "Azərbaycanca",
            unmodifiableList(asList("Özəllik")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Nümunə", "Ssenari")),
            unmodifiableList(asList("Ssenarinin strukturu")),
            unmodifiableList(asList("Keçmiş", "Kontekst")),
            unmodifiableList(asList("Nümunələr")),
            unmodifiableList(asList("* ", "Tutaq ki ", "Verilir ")),
            unmodifiableList(asList("* ", "Əgər ", "Nə vaxt ki ")),
            unmodifiableList(asList("* ", "O halda ")),
            unmodifiableList(asList("* ", "Və ", "Həm ")),
            unmodifiableList(asList("* ", "Amma ", "Ancaq "))
        ));

        dialects.put("be", new GherkinDialect(
            "be",
            "Belarusian",
            "Беларуская",
            unmodifiableList(asList("Функцыянальнасць", "Фіча")),
            unmodifiableList(asList("Правілы")),
            unmodifiableList(asList("Сцэнарый", "Cцэнар")),
            unmodifiableList(asList("Шаблон сцэнарыя", "Узор сцэнара")),
            unmodifiableList(asList("Кантэкст")),
            unmodifiableList(asList("Прыклады")),
            unmodifiableList(asList("* ", "Няхай ", "Дадзена ")),
            unmodifiableList(asList("* ", "Калі ")),
            unmodifiableList(asList("* ", "Тады ")),
            unmodifiableList(asList("* ", "I ", "Ды ", "Таксама ")),
            unmodifiableList(asList("* ", "Але ", "Інакш "))
        ));

        dialects.put("bg", new GherkinDialect(
            "bg",
            "Bulgarian",
            "български",
            unmodifiableList(asList("Функционалност")),
            unmodifiableList(asList("Правило")),
            unmodifiableList(asList("Пример", "Сценарий")),
            unmodifiableList(asList("Рамка на сценарий")),
            unmodifiableList(asList("Предистория")),
            unmodifiableList(asList("Примери")),
            unmodifiableList(asList("* ", "Дадено ")),
            unmodifiableList(asList("* ", "Когато ")),
            unmodifiableList(asList("* ", "То ")),
            unmodifiableList(asList("* ", "И ")),
            unmodifiableList(asList("* ", "Но "))
        ));

        dialects.put("bm", new GherkinDialect(
            "bm",
            "Malay",
            "Bahasa Melayu",
            unmodifiableList(asList("Fungsi")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Senario", "Situasi", "Keadaan")),
            unmodifiableList(asList("Kerangka Senario", "Kerangka Situasi", "Kerangka Keadaan", "Garis Panduan Senario")),
            unmodifiableList(asList("Latar Belakang")),
            unmodifiableList(asList("Contoh")),
            unmodifiableList(asList("* ", "Diberi ", "Bagi ")),
            unmodifiableList(asList("* ", "Apabila ")),
            unmodifiableList(asList("* ", "Maka ", "Kemudian ")),
            unmodifiableList(asList("* ", "Dan ")),
            unmodifiableList(asList("* ", "Tetapi ", "Tapi "))
        ));

        dialects.put("bs", new GherkinDialect(
            "bs",
            "Bosnian",
            "Bosanski",
            unmodifiableList(asList("Karakteristika")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Primjer", "Scenariju", "Scenario")),
            unmodifiableList(asList("Scenariju-obris", "Scenario-outline")),
            unmodifiableList(asList("Pozadina")),
            unmodifiableList(asList("Primjeri")),
            unmodifiableList(asList("* ", "Dato ")),
            unmodifiableList(asList("* ", "Kada ")),
            unmodifiableList(asList("* ", "Zatim ")),
            unmodifiableList(asList("* ", "I ", "A ")),
            unmodifiableList(asList("* ", "Ali "))
        ));

        dialects.put("ca", new GherkinDialect(
            "ca",
            "Catalan",
            "català",
            unmodifiableList(asList("Característica", "Funcionalitat")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Exemple", "Escenari")),
            unmodifiableList(asList("Esquema de l'escenari")),
            unmodifiableList(asList("Rerefons", "Antecedents")),
            unmodifiableList(asList("Exemples")),
            unmodifiableList(asList("* ", "Donat ", "Donada ", "Atès ", "Atesa ")),
            unmodifiableList(asList("* ", "Quan ")),
            unmodifiableList(asList("* ", "Aleshores ", "Cal ")),
            unmodifiableList(asList("* ", "I ")),
            unmodifiableList(asList("* ", "Però "))
        ));

        dialects.put("cs", new GherkinDialect(
            "cs",
            "Czech",
            "Česky",
            unmodifiableList(asList("Požadavek")),
            unmodifiableList(asList("Pravidlo")),
            unmodifiableList(asList("Příklad", "Scénář")),
            unmodifiableList(asList("Náčrt Scénáře", "Osnova scénáře")),
            unmodifiableList(asList("Pozadí", "Kontext")),
            unmodifiableList(asList("Příklady")),
            unmodifiableList(asList("* ", "Pokud ", "Za předpokladu ")),
            unmodifiableList(asList("* ", "Když ")),
            unmodifiableList(asList("* ", "Pak ")),
            unmodifiableList(asList("* ", "A také ", "A ")),
            unmodifiableList(asList("* ", "Ale "))
        ));

        dialects.put("cy-GB", new GherkinDialect(
            "cy-GB",
            "Welsh",
            "Cymraeg",
            unmodifiableList(asList("Arwedd")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Enghraifft", "Scenario")),
            unmodifiableList(asList("Scenario Amlinellol")),
            unmodifiableList(asList("Cefndir")),
            unmodifiableList(asList("Enghreifftiau")),
            unmodifiableList(asList("* ", "Anrhegedig a ")),
            unmodifiableList(asList("* ", "Pryd ")),
            unmodifiableList(asList("* ", "Yna ")),
            unmodifiableList(asList("* ", "A ")),
            unmodifiableList(asList("* ", "Ond "))
        ));

        dialects.put("da", new GherkinDialect(
            "da",
            "Danish",
            "dansk",
            unmodifiableList(asList("Egenskab")),
            unmodifiableList(asList("Regel")),
            unmodifiableList(asList("Eksempel", "Scenarie")),
            unmodifiableList(asList("Abstrakt Scenario")),
            unmodifiableList(asList("Baggrund")),
            unmodifiableList(asList("Eksempler")),
            unmodifiableList(asList("* ", "Givet ")),
            unmodifiableList(asList("* ", "Når ")),
            unmodifiableList(asList("* ", "Så ")),
            unmodifiableList(asList("* ", "Og ")),
            unmodifiableList(asList("* ", "Men "))
        ));

        dialects.put("de", new GherkinDialect(
            "de",
            "German",
            "Deutsch",
            unmodifiableList(asList("Funktionalität", "Funktion")),
            unmodifiableList(asList("Rule", "Regel")),
            unmodifiableList(asList("Beispiel", "Szenario")),
            unmodifiableList(asList("Szenariogrundriss", "Szenarien")),
            unmodifiableList(asList("Grundlage", "Hintergrund", "Voraussetzungen", "Vorbedingungen")),
            unmodifiableList(asList("Beispiele")),
            unmodifiableList(asList("* ", "Angenommen ", "Gegeben sei ", "Gegeben seien ")),
            unmodifiableList(asList("* ", "Wenn ")),
            unmodifiableList(asList("* ", "Dann ")),
            unmodifiableList(asList("* ", "Und ")),
            unmodifiableList(asList("* ", "Aber "))
        ));

        dialects.put("el", new GherkinDialect(
            "el",
            "Greek",
            "Ελληνικά",
            unmodifiableList(asList("Δυνατότητα", "Λειτουργία")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Παράδειγμα", "Σενάριο")),
            unmodifiableList(asList("Περιγραφή Σεναρίου", "Περίγραμμα Σεναρίου")),
            unmodifiableList(asList("Υπόβαθρο")),
            unmodifiableList(asList("Παραδείγματα", "Σενάρια")),
            unmodifiableList(asList("* ", "Δεδομένου ")),
            unmodifiableList(asList("* ", "Όταν ")),
            unmodifiableList(asList("* ", "Τότε ")),
            unmodifiableList(asList("* ", "Και ")),
            unmodifiableList(asList("* ", "Αλλά "))
        ));

        dialects.put("em", new GherkinDialect(
            "em",
            "Emoji",
            "😀",
            unmodifiableList(asList("📚")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("🥒", "📕")),
            unmodifiableList(asList("📖")),
            unmodifiableList(asList("💤")),
            unmodifiableList(asList("📓")),
            unmodifiableList(asList("* ", "😐")),
            unmodifiableList(asList("* ", "🎬")),
            unmodifiableList(asList("* ", "🙏")),
            unmodifiableList(asList("* ", "😂")),
            unmodifiableList(asList("* ", "😔"))
        ));

        dialects.put("en", new GherkinDialect(
            "en",
            "English",
            "English",
            unmodifiableList(asList("Feature", "Business Need", "Ability")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Example", "Scenario")),
            unmodifiableList(asList("Scenario Outline", "Scenario Template")),
            unmodifiableList(asList("Background")),
            unmodifiableList(asList("Examples", "Scenarios")),
            unmodifiableList(asList("* ", "Given ", ":", "@IF:",  "@RUN-ON-FAIL:" , "@RUN-ON-PASS:" , "@ALWAYS-RUN:" , "@RUN-IF:", "@RUN-NEXT-STEPS-IF:" , "@RUN-NEXT-STEPS-WHEN:" , "@POST-SCENARIO-STEPS:"  )),
            unmodifiableList(asList("* ", "When "  )),
            unmodifiableList(asList("* ", "Then ")),
            unmodifiableList(asList("* ", "And ")),
            unmodifiableList(asList("* ", "But "))
        ));

        dialects.put("en-Scouse", new GherkinDialect(
            "en-Scouse",
            "Scouse",
            "Scouse",
            unmodifiableList(asList("Feature")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("The thing of it is")),
            unmodifiableList(asList("Wharrimean is")),
            unmodifiableList(asList("Dis is what went down")),
            unmodifiableList(asList("Examples")),
            unmodifiableList(asList("* ", "Givun ", "Youse know when youse got ")),
            unmodifiableList(asList("* ", "Wun ", "Youse know like when ")),
            unmodifiableList(asList("* ", "Dun ", "Den youse gotta ")),
            unmodifiableList(asList("* ", "An ")),
            unmodifiableList(asList("* ", "Buh "))
        ));

        dialects.put("en-au", new GherkinDialect(
            "en-au",
            "Australian",
            "Australian",
            unmodifiableList(asList("Pretty much")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Awww, look mate")),
            unmodifiableList(asList("Reckon it's like")),
            unmodifiableList(asList("First off")),
            unmodifiableList(asList("You'll wanna")),
            unmodifiableList(asList("* ", "Y'know ")),
            unmodifiableList(asList("* ", "It's just unbelievable ")),
            unmodifiableList(asList("* ", "But at the end of the day I reckon ")),
            unmodifiableList(asList("* ", "Too right ")),
            unmodifiableList(asList("* ", "Yeah nah "))
        ));

        dialects.put("en-lol", new GherkinDialect(
            "en-lol",
            "LOLCAT",
            "LOLCAT",
            unmodifiableList(asList("OH HAI")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("MISHUN")),
            unmodifiableList(asList("MISHUN SRSLY")),
            unmodifiableList(asList("B4")),
            unmodifiableList(asList("EXAMPLZ")),
            unmodifiableList(asList("* ", "I CAN HAZ ")),
            unmodifiableList(asList("* ", "WEN ")),
            unmodifiableList(asList("* ", "DEN ")),
            unmodifiableList(asList("* ", "AN ")),
            unmodifiableList(asList("* ", "BUT "))
        ));

        dialects.put("en-old", new GherkinDialect(
            "en-old",
            "Old English",
            "Englisc",
            unmodifiableList(asList("Hwaet", "Hwæt")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Swa")),
            unmodifiableList(asList("Swa hwaer swa", "Swa hwær swa")),
            unmodifiableList(asList("Aer", "Ær")),
            unmodifiableList(asList("Se the", "Se þe", "Se ðe")),
            unmodifiableList(asList("* ", "Thurh ", "Þurh ", "Ðurh ")),
            unmodifiableList(asList("* ", "Bæþsealf ", "Bæþsealfa ", "Bæþsealfe ", "Ciricæw ", "Ciricæwe ", "Ciricæwa ")),
            unmodifiableList(asList("* ", "Tha ", "Þa ", "Ða ", "Tha the ", "Þa þe ", "Ða ðe ")),
            unmodifiableList(asList("* ", "Ond ", "7 ")),
            unmodifiableList(asList("* ", "Ac "))
        ));

        dialects.put("en-pirate", new GherkinDialect(
            "en-pirate",
            "Pirate",
            "Pirate",
            unmodifiableList(asList("Ahoy matey!")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Heave to")),
            unmodifiableList(asList("Shiver me timbers")),
            unmodifiableList(asList("Yo-ho-ho")),
            unmodifiableList(asList("Dead men tell no tales")),
            unmodifiableList(asList("* ", "Gangway! ")),
            unmodifiableList(asList("* ", "Blimey! ")),
            unmodifiableList(asList("* ", "Let go and haul ")),
            unmodifiableList(asList("* ", "Aye ")),
            unmodifiableList(asList("* ", "Avast! "))
        ));

        dialects.put("en-tx", new GherkinDialect(
            "en-tx",
            "Texas",
            "Texas",
            unmodifiableList(asList("This ain’t my first rodeo", "All gussied up")),
            unmodifiableList(asList("Rule ")),
            unmodifiableList(asList("All hat and no cattle")),
            unmodifiableList(asList("Serious as a snake bite", "Busy as a hound in flea season")),
            unmodifiableList(asList("Lemme tell y'all a story")),
            unmodifiableList(asList("Now that's a story longer than a cattle drive in July")),
            unmodifiableList(asList("Fixin' to ", "All git out ")),
            unmodifiableList(asList("Quick out of the chute ")),
            unmodifiableList(asList("There’s no tree but bears some fruit ")),
            unmodifiableList(asList("Come hell or high water ")),
            unmodifiableList(asList("Well now hold on, I'll you what "))
        ));

        dialects.put("eo", new GherkinDialect(
            "eo",
            "Esperanto",
            "Esperanto",
            unmodifiableList(asList("Trajto")),
            unmodifiableList(asList("Regulo")),
            unmodifiableList(asList("Ekzemplo", "Scenaro", "Kazo")),
            unmodifiableList(asList("Konturo de la scenaro", "Skizo", "Kazo-skizo")),
            unmodifiableList(asList("Fono")),
            unmodifiableList(asList("Ekzemploj")),
            unmodifiableList(asList("* ", "Donitaĵo ", "Komence ")),
            unmodifiableList(asList("* ", "Se ")),
            unmodifiableList(asList("* ", "Do ")),
            unmodifiableList(asList("* ", "Kaj ")),
            unmodifiableList(asList("* ", "Sed "))
        ));

        dialects.put("es", new GherkinDialect(
            "es",
            "Spanish",
            "español",
            unmodifiableList(asList("Característica", "Necesidad del negocio", "Requisito")),
            unmodifiableList(asList("Regla", "Regla de negocio")),
            unmodifiableList(asList("Ejemplo", "Escenario")),
            unmodifiableList(asList("Esquema del escenario")),
            unmodifiableList(asList("Antecedentes")),
            unmodifiableList(asList("Ejemplos")),
            unmodifiableList(asList("* ", "Dado ", "Dada ", "Dados ", "Dadas ")),
            unmodifiableList(asList("* ", "Cuando ")),
            unmodifiableList(asList("* ", "Entonces ")),
            unmodifiableList(asList("* ", "Y ", "E ")),
            unmodifiableList(asList("* ", "Pero "))
        ));

        dialects.put("et", new GherkinDialect(
            "et",
            "Estonian",
            "eesti keel",
            unmodifiableList(asList("Omadus")),
            unmodifiableList(asList("Reegel")),
            unmodifiableList(asList("Juhtum", "Stsenaarium")),
            unmodifiableList(asList("Raamjuhtum", "Raamstsenaarium")),
            unmodifiableList(asList("Taust")),
            unmodifiableList(asList("Juhtumid")),
            unmodifiableList(asList("* ", "Eeldades ")),
            unmodifiableList(asList("* ", "Kui ")),
            unmodifiableList(asList("* ", "Siis ")),
            unmodifiableList(asList("* ", "Ja ")),
            unmodifiableList(asList("* ", "Kuid "))
        ));

        dialects.put("fa", new GherkinDialect(
            "fa",
            "Persian",
            "فارسی",
            unmodifiableList(asList("وِیژگی")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("مثال", "سناریو")),
            unmodifiableList(asList("الگوی سناریو")),
            unmodifiableList(asList("زمینه")),
            unmodifiableList(asList("نمونه ها")),
            unmodifiableList(asList("* ", "با فرض ")),
            unmodifiableList(asList("* ", "هنگامی ")),
            unmodifiableList(asList("* ", "آنگاه ")),
            unmodifiableList(asList("* ", "و ")),
            unmodifiableList(asList("* ", "اما "))
        ));

        dialects.put("fi", new GherkinDialect(
            "fi",
            "Finnish",
            "suomi",
            unmodifiableList(asList("Ominaisuus")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Tapaus")),
            unmodifiableList(asList("Tapausaihio")),
            unmodifiableList(asList("Tausta")),
            unmodifiableList(asList("Tapaukset")),
            unmodifiableList(asList("* ", "Oletetaan ")),
            unmodifiableList(asList("* ", "Kun ")),
            unmodifiableList(asList("* ", "Niin ")),
            unmodifiableList(asList("* ", "Ja ")),
            unmodifiableList(asList("* ", "Mutta "))
        ));

        dialects.put("fr", new GherkinDialect(
            "fr",
            "French",
            "français",
            unmodifiableList(asList("Fonctionnalité")),
            unmodifiableList(asList("Règle")),
            unmodifiableList(asList("Exemple", "Scénario")),
            unmodifiableList(asList("Plan du scénario", "Plan du Scénario")),
            unmodifiableList(asList("Contexte")),
            unmodifiableList(asList("Exemples")),
            unmodifiableList(asList("* ", "Soit ", "Sachant que ", "Sachant qu'", "Sachant ", "Etant donné que ", "Etant donné qu'", "Etant donné ", "Etant donnée ", "Etant donnés ", "Etant données ", "Étant donné que ", "Étant donné qu'", "Étant donné ", "Étant donnée ", "Étant donnés ", "Étant données ")),
            unmodifiableList(asList("* ", "Quand ", "Lorsque ", "Lorsqu'")),
            unmodifiableList(asList("* ", "Alors ", "Donc ")),
            unmodifiableList(asList("* ", "Et que ", "Et qu'", "Et ")),
            unmodifiableList(asList("* ", "Mais que ", "Mais qu'", "Mais "))
        ));

        dialects.put("ga", new GherkinDialect(
            "ga",
            "Irish",
            "Gaeilge",
            unmodifiableList(asList("Gné")),
            unmodifiableList(asList("Riail")),
            unmodifiableList(asList("Sampla", "Cás")),
            unmodifiableList(asList("Cás Achomair")),
            unmodifiableList(asList("Cúlra")),
            unmodifiableList(asList("Samplaí")),
            unmodifiableList(asList("* ", "Cuir i gcás go", "Cuir i gcás nach", "Cuir i gcás gur", "Cuir i gcás nár")),
            unmodifiableList(asList("* ", "Nuair a", "Nuair nach", "Nuair ba", "Nuair nár")),
            unmodifiableList(asList("* ", "Ansin")),
            unmodifiableList(asList("* ", "Agus")),
            unmodifiableList(asList("* ", "Ach"))
        ));

        dialects.put("gj", new GherkinDialect(
            "gj",
            "Gujarati",
            "ગુજરાતી",
            unmodifiableList(asList("લક્ષણ", "વ્યાપાર જરૂર", "ક્ષમતા")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("ઉદાહરણ", "સ્થિતિ")),
            unmodifiableList(asList("પરિદ્દશ્ય રૂપરેખા", "પરિદ્દશ્ય ઢાંચો")),
            unmodifiableList(asList("બેકગ્રાઉન્ડ")),
            unmodifiableList(asList("ઉદાહરણો")),
            unmodifiableList(asList("* ", "આપેલ છે ")),
            unmodifiableList(asList("* ", "ક્યારે ")),
            unmodifiableList(asList("* ", "પછી ")),
            unmodifiableList(asList("* ", "અને ")),
            unmodifiableList(asList("* ", "પણ "))
        ));

        dialects.put("gl", new GherkinDialect(
            "gl",
            "Galician",
            "galego",
            unmodifiableList(asList("Característica")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Exemplo", "Escenario")),
            unmodifiableList(asList("Esbozo do escenario")),
            unmodifiableList(asList("Contexto")),
            unmodifiableList(asList("Exemplos")),
            unmodifiableList(asList("* ", "Dado ", "Dada ", "Dados ", "Dadas ")),
            unmodifiableList(asList("* ", "Cando ")),
            unmodifiableList(asList("* ", "Entón ", "Logo ")),
            unmodifiableList(asList("* ", "E ")),
            unmodifiableList(asList("* ", "Mais ", "Pero "))
        ));

        dialects.put("he", new GherkinDialect(
            "he",
            "Hebrew",
            "עברית",
            unmodifiableList(asList("תכונה")),
            unmodifiableList(asList("כלל")),
            unmodifiableList(asList("דוגמא", "תרחיש")),
            unmodifiableList(asList("תבנית תרחיש")),
            unmodifiableList(asList("רקע")),
            unmodifiableList(asList("דוגמאות")),
            unmodifiableList(asList("* ", "בהינתן ")),
            unmodifiableList(asList("* ", "כאשר ")),
            unmodifiableList(asList("* ", "אז ", "אזי ")),
            unmodifiableList(asList("* ", "וגם ")),
            unmodifiableList(asList("* ", "אבל "))
        ));

        dialects.put("hi", new GherkinDialect(
            "hi",
            "Hindi",
            "हिंदी",
            unmodifiableList(asList("रूप लेख")),
            unmodifiableList(asList("नियम")),
            unmodifiableList(asList("परिदृश्य")),
            unmodifiableList(asList("परिदृश्य रूपरेखा")),
            unmodifiableList(asList("पृष्ठभूमि")),
            unmodifiableList(asList("उदाहरण")),
            unmodifiableList(asList("* ", "अगर ", "यदि ", "चूंकि ")),
            unmodifiableList(asList("* ", "जब ", "कदा ")),
            unmodifiableList(asList("* ", "तब ", "तदा ")),
            unmodifiableList(asList("* ", "और ", "तथा ")),
            unmodifiableList(asList("* ", "पर ", "परन्तु ", "किन्तु "))
        ));

        dialects.put("hr", new GherkinDialect(
            "hr",
            "Croatian",
            "hrvatski",
            unmodifiableList(asList("Osobina", "Mogućnost", "Mogucnost")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Primjer", "Scenarij")),
            unmodifiableList(asList("Skica", "Koncept")),
            unmodifiableList(asList("Pozadina")),
            unmodifiableList(asList("Primjeri", "Scenariji")),
            unmodifiableList(asList("* ", "Zadan ", "Zadani ", "Zadano ", "Ukoliko ")),
            unmodifiableList(asList("* ", "Kada ", "Kad ")),
            unmodifiableList(asList("* ", "Onda ")),
            unmodifiableList(asList("* ", "I ")),
            unmodifiableList(asList("* ", "Ali "))
        ));

        dialects.put("ht", new GherkinDialect(
            "ht",
            "Creole",
            "kreyòl",
            unmodifiableList(asList("Karakteristik", "Mak", "Fonksyonalite")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Senaryo")),
            unmodifiableList(asList("Plan senaryo", "Plan Senaryo", "Senaryo deskripsyon", "Senaryo Deskripsyon", "Dyagram senaryo", "Dyagram Senaryo")),
            unmodifiableList(asList("Kontèks", "Istorik")),
            unmodifiableList(asList("Egzanp")),
            unmodifiableList(asList("* ", "Sipoze ", "Sipoze ke ", "Sipoze Ke ")),
            unmodifiableList(asList("* ", "Lè ", "Le ")),
            unmodifiableList(asList("* ", "Lè sa a ", "Le sa a ")),
            unmodifiableList(asList("* ", "Ak ", "Epi ", "E ")),
            unmodifiableList(asList("* ", "Men "))
        ));

        dialects.put("hu", new GherkinDialect(
            "hu",
            "Hungarian",
            "magyar",
            unmodifiableList(asList("Jellemző")),
            unmodifiableList(asList("Szabály")),
            unmodifiableList(asList("Példa", "Forgatókönyv")),
            unmodifiableList(asList("Forgatókönyv vázlat")),
            unmodifiableList(asList("Háttér")),
            unmodifiableList(asList("Példák")),
            unmodifiableList(asList("* ", "Amennyiben ", "Adott ")),
            unmodifiableList(asList("* ", "Majd ", "Ha ", "Amikor ")),
            unmodifiableList(asList("* ", "Akkor ")),
            unmodifiableList(asList("* ", "És ")),
            unmodifiableList(asList("* ", "De "))
        ));

        dialects.put("id", new GherkinDialect(
            "id",
            "Indonesian",
            "Bahasa Indonesia",
            unmodifiableList(asList("Fitur")),
            unmodifiableList(asList("Rule", "Aturan")),
            unmodifiableList(asList("Skenario")),
            unmodifiableList(asList("Skenario konsep", "Garis-Besar Skenario")),
            unmodifiableList(asList("Dasar", "Latar Belakang")),
            unmodifiableList(asList("Contoh", "Misal")),
            unmodifiableList(asList("* ", "Dengan ", "Diketahui ", "Diasumsikan ", "Bila ", "Jika ")),
            unmodifiableList(asList("* ", "Ketika ")),
            unmodifiableList(asList("* ", "Maka ", "Kemudian ")),
            unmodifiableList(asList("* ", "Dan ")),
            unmodifiableList(asList("* ", "Tapi ", "Tetapi "))
        ));

        dialects.put("is", new GherkinDialect(
            "is",
            "Icelandic",
            "Íslenska",
            unmodifiableList(asList("Eiginleiki")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Atburðarás")),
            unmodifiableList(asList("Lýsing Atburðarásar", "Lýsing Dæma")),
            unmodifiableList(asList("Bakgrunnur")),
            unmodifiableList(asList("Dæmi", "Atburðarásir")),
            unmodifiableList(asList("* ", "Ef ")),
            unmodifiableList(asList("* ", "Þegar ")),
            unmodifiableList(asList("* ", "Þá ")),
            unmodifiableList(asList("* ", "Og ")),
            unmodifiableList(asList("* ", "En "))
        ));

        dialects.put("it", new GherkinDialect(
            "it",
            "Italian",
            "italiano",
            unmodifiableList(asList("Funzionalità", "Esigenza di Business", "Abilità")),
            unmodifiableList(asList("Regola")),
            unmodifiableList(asList("Esempio", "Scenario")),
            unmodifiableList(asList("Schema dello scenario")),
            unmodifiableList(asList("Contesto")),
            unmodifiableList(asList("Esempi")),
            unmodifiableList(asList("* ", "Dato ", "Data ", "Dati ", "Date ")),
            unmodifiableList(asList("* ", "Quando ")),
            unmodifiableList(asList("* ", "Allora ")),
            unmodifiableList(asList("* ", "E ", "Ed ")),
            unmodifiableList(asList("* ", "Ma "))
        ));

        dialects.put("ja", new GherkinDialect(
            "ja",
            "Japanese",
            "日本語",
            unmodifiableList(asList("フィーチャ", "機能")),
            unmodifiableList(asList("ルール")),
            unmodifiableList(asList("シナリオ")),
            unmodifiableList(asList("シナリオアウトライン", "シナリオテンプレート", "テンプレ", "シナリオテンプレ")),
            unmodifiableList(asList("背景")),
            unmodifiableList(asList("例", "サンプル")),
            unmodifiableList(asList("* ", "前提")),
            unmodifiableList(asList("* ", "もし")),
            unmodifiableList(asList("* ", "ならば")),
            unmodifiableList(asList("* ", "且つ", "かつ")),
            unmodifiableList(asList("* ", "然し", "しかし", "但し", "ただし"))
        ));

        dialects.put("jv", new GherkinDialect(
            "jv",
            "Javanese",
            "Basa Jawa",
            unmodifiableList(asList("Fitur")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Skenario")),
            unmodifiableList(asList("Konsep skenario")),
            unmodifiableList(asList("Dasar")),
            unmodifiableList(asList("Conto", "Contone")),
            unmodifiableList(asList("* ", "Nalika ", "Nalikaning ")),
            unmodifiableList(asList("* ", "Manawa ", "Menawa ")),
            unmodifiableList(asList("* ", "Njuk ", "Banjur ")),
            unmodifiableList(asList("* ", "Lan ")),
            unmodifiableList(asList("* ", "Tapi ", "Nanging ", "Ananging "))
        ));

        dialects.put("ka", new GherkinDialect(
            "ka",
            "Georgian",
            "ქართული",
            unmodifiableList(asList("თვისება", "მოთხოვნა")),
            unmodifiableList(asList("წესი")),
            unmodifiableList(asList("მაგალითად", "მაგალითი", "მაგ", "სცენარი")),
            unmodifiableList(asList("სცენარის ნიმუში", "სცენარის შაბლონი", "ნიმუში", "შაბლონი")),
            unmodifiableList(asList("კონტექსტი")),
            unmodifiableList(asList("მაგალითები")),
            unmodifiableList(asList("* ", "მოცემული ", "მოცემულია ", "ვთქვათ ")),
            unmodifiableList(asList("* ", "როდესაც ", "როცა ", "როგორც კი ", "თუ ")),
            unmodifiableList(asList("* ", "მაშინ ")),
            unmodifiableList(asList("* ", "და ", "ასევე ")),
            unmodifiableList(asList("* ", "მაგრამ ", "თუმცა "))
        ));

        dialects.put("kn", new GherkinDialect(
            "kn",
            "Kannada",
            "ಕನ್ನಡ",
            unmodifiableList(asList("ಹೆಚ್ಚಳ")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("ಉದಾಹರಣೆ", "ಕಥಾಸಾರಾಂಶ")),
            unmodifiableList(asList("ವಿವರಣೆ")),
            unmodifiableList(asList("ಹಿನ್ನೆಲೆ")),
            unmodifiableList(asList("ಉದಾಹರಣೆಗಳು")),
            unmodifiableList(asList("* ", "ನೀಡಿದ ")),
            unmodifiableList(asList("* ", "ಸ್ಥಿತಿಯನ್ನು ")),
            unmodifiableList(asList("* ", "ನಂತರ ")),
            unmodifiableList(asList("* ", "ಮತ್ತು ")),
            unmodifiableList(asList("* ", "ಆದರೆ "))
        ));

        dialects.put("ko", new GherkinDialect(
            "ko",
            "Korean",
            "한국어",
            unmodifiableList(asList("기능")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("시나리오")),
            unmodifiableList(asList("시나리오 개요")),
            unmodifiableList(asList("배경")),
            unmodifiableList(asList("예")),
            unmodifiableList(asList("* ", "조건", "먼저")),
            unmodifiableList(asList("* ", "만일", "만약")),
            unmodifiableList(asList("* ", "그러면")),
            unmodifiableList(asList("* ", "그리고")),
            unmodifiableList(asList("* ", "하지만", "단"))
        ));

        dialects.put("lt", new GherkinDialect(
            "lt",
            "Lithuanian",
            "lietuvių kalba",
            unmodifiableList(asList("Savybė")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Pavyzdys", "Scenarijus")),
            unmodifiableList(asList("Scenarijaus šablonas")),
            unmodifiableList(asList("Kontekstas")),
            unmodifiableList(asList("Pavyzdžiai", "Scenarijai", "Variantai")),
            unmodifiableList(asList("* ", "Duota ")),
            unmodifiableList(asList("* ", "Kai ")),
            unmodifiableList(asList("* ", "Tada ")),
            unmodifiableList(asList("* ", "Ir ")),
            unmodifiableList(asList("* ", "Bet "))
        ));

        dialects.put("lu", new GherkinDialect(
            "lu",
            "Luxemburgish",
            "Lëtzebuergesch",
            unmodifiableList(asList("Funktionalitéit")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Beispill", "Szenario")),
            unmodifiableList(asList("Plang vum Szenario")),
            unmodifiableList(asList("Hannergrond")),
            unmodifiableList(asList("Beispiller")),
            unmodifiableList(asList("* ", "ugeholl ")),
            unmodifiableList(asList("* ", "wann ")),
            unmodifiableList(asList("* ", "dann ")),
            unmodifiableList(asList("* ", "an ", "a ")),
            unmodifiableList(asList("* ", "awer ", "mä "))
        ));

        dialects.put("lv", new GherkinDialect(
            "lv",
            "Latvian",
            "latviešu",
            unmodifiableList(asList("Funkcionalitāte", "Fīča")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Piemērs", "Scenārijs")),
            unmodifiableList(asList("Scenārijs pēc parauga")),
            unmodifiableList(asList("Konteksts", "Situācija")),
            unmodifiableList(asList("Piemēri", "Paraugs")),
            unmodifiableList(asList("* ", "Kad ")),
            unmodifiableList(asList("* ", "Ja ")),
            unmodifiableList(asList("* ", "Tad ")),
            unmodifiableList(asList("* ", "Un ")),
            unmodifiableList(asList("* ", "Bet "))
        ));

        dialects.put("mk-Cyrl", new GherkinDialect(
            "mk-Cyrl",
            "Macedonian",
            "Македонски",
            unmodifiableList(asList("Функционалност", "Бизнис потреба", "Можност")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Пример", "Сценарио", "На пример")),
            unmodifiableList(asList("Преглед на сценарија", "Скица", "Концепт")),
            unmodifiableList(asList("Контекст", "Содржина")),
            unmodifiableList(asList("Примери", "Сценарија")),
            unmodifiableList(asList("* ", "Дадено ", "Дадена ")),
            unmodifiableList(asList("* ", "Кога ")),
            unmodifiableList(asList("* ", "Тогаш ")),
            unmodifiableList(asList("* ", "И ")),
            unmodifiableList(asList("* ", "Но "))
        ));

        dialects.put("mk-Latn", new GherkinDialect(
            "mk-Latn",
            "Macedonian (Latin)",
            "Makedonski (Latinica)",
            unmodifiableList(asList("Funkcionalnost", "Biznis potreba", "Mozhnost")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Scenario", "Na primer")),
            unmodifiableList(asList("Pregled na scenarija", "Skica", "Koncept")),
            unmodifiableList(asList("Kontekst", "Sodrzhina")),
            unmodifiableList(asList("Primeri", "Scenaria")),
            unmodifiableList(asList("* ", "Dadeno ", "Dadena ")),
            unmodifiableList(asList("* ", "Koga ")),
            unmodifiableList(asList("* ", "Togash ")),
            unmodifiableList(asList("* ", "I ")),
            unmodifiableList(asList("* ", "No "))
        ));

        dialects.put("ml", new GherkinDialect(
            "ml",
            "Malayalam",
            "മലയാളം",
            unmodifiableList(asList("സവിശേഷത")),
            unmodifiableList(asList("നിയമം")),
            unmodifiableList(asList("രംഗം")),
            unmodifiableList(asList("സാഹചര്യത്തിന്റെ രൂപരേഖ")),
            unmodifiableList(asList("പശ്ചാത്തലം")),
            unmodifiableList(asList("ഉദാഹരണങ്ങൾ")),
            unmodifiableList(asList("* ", "നൽകിയത്")),
            unmodifiableList(asList("എപ്പോൾ")),
            unmodifiableList(asList("* ", "പിന്നെ")),
            unmodifiableList(asList("* ", "ഒപ്പം")),
            unmodifiableList(asList("* ", "പക്ഷേ"))
        ));

        dialects.put("mn", new GherkinDialect(
            "mn",
            "Mongolian",
            "монгол",
            unmodifiableList(asList("Функц", "Функционал")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Сценар")),
            unmodifiableList(asList("Сценарын төлөвлөгөө")),
            unmodifiableList(asList("Агуулга")),
            unmodifiableList(asList("Тухайлбал")),
            unmodifiableList(asList("* ", "Өгөгдсөн нь ", "Анх ")),
            unmodifiableList(asList("* ", "Хэрэв ")),
            unmodifiableList(asList("* ", "Тэгэхэд ", "Үүний дараа ")),
            unmodifiableList(asList("* ", "Мөн ", "Тэгээд ")),
            unmodifiableList(asList("* ", "Гэхдээ ", "Харин "))
        ));

        dialects.put("mr", new GherkinDialect(
            "mr",
            "Marathi",
            "मराठी",
            unmodifiableList(asList("वैशिष्ट्य", "सुविधा")),
            unmodifiableList(asList("नियम")),
            unmodifiableList(asList("परिदृश्य")),
            unmodifiableList(asList("परिदृश्य रूपरेखा")),
            unmodifiableList(asList("पार्श्वभूमी")),
            unmodifiableList(asList("उदाहरण")),
            unmodifiableList(asList("* ", "जर", "दिलेल्या प्रमाणे ")),
            unmodifiableList(asList("* ", "जेव्हा ")),
            unmodifiableList(asList("* ", "मग ", "तेव्हा ")),
            unmodifiableList(asList("* ", "आणि ", "तसेच ")),
            unmodifiableList(asList("* ", "पण ", "परंतु "))
        ));

        dialects.put("ne", new GherkinDialect(
            "ne",
            "Nepali",
            "नेपाली",
            unmodifiableList(asList("सुविधा", "विशेषता")),
            unmodifiableList(asList("नियम")),
            unmodifiableList(asList("परिदृश्य")),
            unmodifiableList(asList("परिदृश्य रूपरेखा")),
            unmodifiableList(asList("पृष्ठभूमी")),
            unmodifiableList(asList("उदाहरण", "उदाहरणहरु")),
            unmodifiableList(asList("* ", "दिइएको ", "दिएको ", "यदि ")),
            unmodifiableList(asList("* ", "जब ")),
            unmodifiableList(asList("* ", "त्यसपछि ", "अनी ")),
            unmodifiableList(asList("* ", "र ", "अनि ")),
            unmodifiableList(asList("* ", "तर "))
        ));

        dialects.put("nl", new GherkinDialect(
            "nl",
            "Dutch",
            "Nederlands",
            unmodifiableList(asList("Functionaliteit")),
            unmodifiableList(asList("Regel")),
            unmodifiableList(asList("Voorbeeld", "Scenario")),
            unmodifiableList(asList("Abstract Scenario")),
            unmodifiableList(asList("Achtergrond")),
            unmodifiableList(asList("Voorbeelden")),
            unmodifiableList(asList("* ", "Gegeven ", "Stel ")),
            unmodifiableList(asList("* ", "Als ", "Wanneer ")),
            unmodifiableList(asList("* ", "Dan ")),
            unmodifiableList(asList("* ", "En ")),
            unmodifiableList(asList("* ", "Maar "))
        ));

        dialects.put("no", new GherkinDialect(
            "no",
            "Norwegian",
            "norsk",
            unmodifiableList(asList("Egenskap")),
            unmodifiableList(asList("Regel")),
            unmodifiableList(asList("Eksempel", "Scenario")),
            unmodifiableList(asList("Scenariomal", "Abstrakt Scenario")),
            unmodifiableList(asList("Bakgrunn")),
            unmodifiableList(asList("Eksempler")),
            unmodifiableList(asList("* ", "Gitt ")),
            unmodifiableList(asList("* ", "Når ")),
            unmodifiableList(asList("* ", "Så ")),
            unmodifiableList(asList("* ", "Og ")),
            unmodifiableList(asList("* ", "Men "))
        ));

        dialects.put("pa", new GherkinDialect(
            "pa",
            "Panjabi",
            "ਪੰਜਾਬੀ",
            unmodifiableList(asList("ਖਾਸੀਅਤ", "ਮੁਹਾਂਦਰਾ", "ਨਕਸ਼ ਨੁਹਾਰ")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("ਉਦਾਹਰਨ", "ਪਟਕਥਾ")),
            unmodifiableList(asList("ਪਟਕਥਾ ਢਾਂਚਾ", "ਪਟਕਥਾ ਰੂਪ ਰੇਖਾ")),
            unmodifiableList(asList("ਪਿਛੋਕੜ")),
            unmodifiableList(asList("ਉਦਾਹਰਨਾਂ")),
            unmodifiableList(asList("* ", "ਜੇਕਰ ", "ਜਿਵੇਂ ਕਿ ")),
            unmodifiableList(asList("* ", "ਜਦੋਂ ")),
            unmodifiableList(asList("* ", "ਤਦ ")),
            unmodifiableList(asList("* ", "ਅਤੇ ")),
            unmodifiableList(asList("* ", "ਪਰ "))
        ));

        dialects.put("pl", new GherkinDialect(
            "pl",
            "Polish",
            "polski",
            unmodifiableList(asList("Właściwość", "Funkcja", "Aspekt", "Potrzeba biznesowa")),
            unmodifiableList(asList("Zasada", "Reguła")),
            unmodifiableList(asList("Przykład", "Scenariusz")),
            unmodifiableList(asList("Szablon scenariusza")),
            unmodifiableList(asList("Założenia")),
            unmodifiableList(asList("Przykłady")),
            unmodifiableList(asList("* ", "Zakładając ", "Mając ", "Zakładając, że ")),
            unmodifiableList(asList("* ", "Jeżeli ", "Jeśli ", "Gdy ", "Kiedy ")),
            unmodifiableList(asList("* ", "Wtedy ")),
            unmodifiableList(asList("* ", "Oraz ", "I ")),
            unmodifiableList(asList("* ", "Ale "))
        ));

        dialects.put("pt", new GherkinDialect(
            "pt",
            "Portuguese",
            "português",
            unmodifiableList(asList("Funcionalidade", "Característica", "Caracteristica")),
            unmodifiableList(asList("Regra")),
            unmodifiableList(asList("Exemplo", "Cenário", "Cenario")),
            unmodifiableList(asList("Esquema do Cenário", "Esquema do Cenario", "Delineação do Cenário", "Delineacao do Cenario")),
            unmodifiableList(asList("Contexto", "Cenário de Fundo", "Cenario de Fundo", "Fundo")),
            unmodifiableList(asList("Exemplos", "Cenários", "Cenarios")),
            unmodifiableList(asList("* ", "Dado ", "Dada ", "Dados ", "Dadas ")),
            unmodifiableList(asList("* ", "Quando ")),
            unmodifiableList(asList("* ", "Então ", "Entao ")),
            unmodifiableList(asList("* ", "E ")),
            unmodifiableList(asList("* ", "Mas "))
        ));

        dialects.put("ro", new GherkinDialect(
            "ro",
            "Romanian",
            "română",
            unmodifiableList(asList("Functionalitate", "Funcționalitate", "Funcţionalitate")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Exemplu", "Scenariu")),
            unmodifiableList(asList("Structura scenariu", "Structură scenariu")),
            unmodifiableList(asList("Context")),
            unmodifiableList(asList("Exemple")),
            unmodifiableList(asList("* ", "Date fiind ", "Dat fiind ", "Dată fiind", "Dati fiind ", "Dați fiind ", "Daţi fiind ")),
            unmodifiableList(asList("* ", "Cand ", "Când ")),
            unmodifiableList(asList("* ", "Atunci ")),
            unmodifiableList(asList("* ", "Si ", "Și ", "Şi ")),
            unmodifiableList(asList("* ", "Dar "))
        ));

        dialects.put("ru", new GherkinDialect(
            "ru",
            "Russian",
            "русский",
            unmodifiableList(asList("Функция", "Функциональность", "Функционал", "Свойство", "Фича")),
            unmodifiableList(asList("Правило")),
            unmodifiableList(asList("Пример", "Сценарий")),
            unmodifiableList(asList("Структура сценария", "Шаблон сценария")),
            unmodifiableList(asList("Предыстория", "Контекст")),
            unmodifiableList(asList("Примеры")),
            unmodifiableList(asList("* ", "Допустим ", "Дано ", "Пусть ")),
            unmodifiableList(asList("* ", "Когда ", "Если ")),
            unmodifiableList(asList("* ", "То ", "Затем ", "Тогда ")),
            unmodifiableList(asList("* ", "И ", "К тому же ", "Также ")),
            unmodifiableList(asList("* ", "Но ", "А ", "Иначе "))
        ));

        dialects.put("sk", new GherkinDialect(
            "sk",
            "Slovak",
            "Slovensky",
            unmodifiableList(asList("Požiadavka", "Funkcia", "Vlastnosť")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Príklad", "Scenár")),
            unmodifiableList(asList("Náčrt Scenáru", "Náčrt Scenára", "Osnova Scenára")),
            unmodifiableList(asList("Pozadie")),
            unmodifiableList(asList("Príklady")),
            unmodifiableList(asList("* ", "Pokiaľ ", "Za predpokladu ")),
            unmodifiableList(asList("* ", "Keď ", "Ak ")),
            unmodifiableList(asList("* ", "Tak ", "Potom ")),
            unmodifiableList(asList("* ", "A ", "A tiež ", "A taktiež ", "A zároveň ")),
            unmodifiableList(asList("* ", "Ale "))
        ));

        dialects.put("sl", new GherkinDialect(
            "sl",
            "Slovenian",
            "Slovenski",
            unmodifiableList(asList("Funkcionalnost", "Funkcija", "Možnosti", "Moznosti", "Lastnost", "Značilnost")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Primer", "Scenarij")),
            unmodifiableList(asList("Struktura scenarija", "Skica", "Koncept", "Oris scenarija", "Osnutek")),
            unmodifiableList(asList("Kontekst", "Osnova", "Ozadje")),
            unmodifiableList(asList("Primeri", "Scenariji")),
            unmodifiableList(asList("Dano ", "Podano ", "Zaradi ", "Privzeto ")),
            unmodifiableList(asList("Ko ", "Ce ", "Če ", "Kadar ")),
            unmodifiableList(asList("Nato ", "Potem ", "Takrat ")),
            unmodifiableList(asList("In ", "Ter ")),
            unmodifiableList(asList("Toda ", "Ampak ", "Vendar "))
        ));

        dialects.put("sr-Cyrl", new GherkinDialect(
            "sr-Cyrl",
            "Serbian",
            "Српски",
            unmodifiableList(asList("Функционалност", "Могућност", "Особина")),
            unmodifiableList(asList("Правило")),
            unmodifiableList(asList("Пример", "Сценарио", "Пример")),
            unmodifiableList(asList("Структура сценарија", "Скица", "Концепт")),
            unmodifiableList(asList("Контекст", "Основа", "Позадина")),
            unmodifiableList(asList("Примери", "Сценарији")),
            unmodifiableList(asList("* ", "За дато ", "За дате ", "За дати ")),
            unmodifiableList(asList("* ", "Када ", "Кад ")),
            unmodifiableList(asList("* ", "Онда ")),
            unmodifiableList(asList("* ", "И ")),
            unmodifiableList(asList("* ", "Али "))
        ));

        dialects.put("sr-Latn", new GherkinDialect(
            "sr-Latn",
            "Serbian (Latin)",
            "Srpski (Latinica)",
            unmodifiableList(asList("Funkcionalnost", "Mogućnost", "Mogucnost", "Osobina")),
            unmodifiableList(asList("Pravilo")),
            unmodifiableList(asList("Scenario", "Primer")),
            unmodifiableList(asList("Struktura scenarija", "Skica", "Koncept")),
            unmodifiableList(asList("Kontekst", "Osnova", "Pozadina")),
            unmodifiableList(asList("Primeri", "Scenariji")),
            unmodifiableList(asList("* ", "Za dato ", "Za date ", "Za dati ")),
            unmodifiableList(asList("* ", "Kada ", "Kad ")),
            unmodifiableList(asList("* ", "Onda ")),
            unmodifiableList(asList("* ", "I ")),
            unmodifiableList(asList("* ", "Ali "))
        ));

        dialects.put("sv", new GherkinDialect(
            "sv",
            "Swedish",
            "Svenska",
            unmodifiableList(asList("Egenskap")),
            unmodifiableList(asList("Regel")),
            unmodifiableList(asList("Scenario")),
            unmodifiableList(asList("Abstrakt Scenario", "Scenariomall")),
            unmodifiableList(asList("Bakgrund")),
            unmodifiableList(asList("Exempel")),
            unmodifiableList(asList("* ", "Givet ")),
            unmodifiableList(asList("* ", "När ")),
            unmodifiableList(asList("* ", "Så ")),
            unmodifiableList(asList("* ", "Och ")),
            unmodifiableList(asList("* ", "Men "))
        ));

        dialects.put("ta", new GherkinDialect(
            "ta",
            "Tamil",
            "தமிழ்",
            unmodifiableList(asList("அம்சம்", "வணிக தேவை", "திறன்")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("உதாரணமாக", "காட்சி")),
            unmodifiableList(asList("காட்சி சுருக்கம்", "காட்சி வார்ப்புரு")),
            unmodifiableList(asList("பின்னணி")),
            unmodifiableList(asList("எடுத்துக்காட்டுகள்", "காட்சிகள்", "நிலைமைகளில்")),
            unmodifiableList(asList("* ", "கொடுக்கப்பட்ட ")),
            unmodifiableList(asList("* ", "எப்போது ")),
            unmodifiableList(asList("* ", "அப்பொழுது ")),
            unmodifiableList(asList("* ", "மேலும்  ", "மற்றும் ")),
            unmodifiableList(asList("* ", "ஆனால்  "))
        ));

        dialects.put("te", new GherkinDialect(
            "te",
            "Telugu",
            "తెలుగు",
            unmodifiableList(asList("గుణము")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("ఉదాహరణ", "సన్నివేశం")),
            unmodifiableList(asList("కథనం")),
            unmodifiableList(asList("నేపథ్యం")),
            unmodifiableList(asList("ఉదాహరణలు")),
            unmodifiableList(asList("* ", "చెప్పబడినది ")),
            unmodifiableList(asList("* ", "ఈ పరిస్థితిలో ")),
            unmodifiableList(asList("* ", "అప్పుడు ")),
            unmodifiableList(asList("* ", "మరియు ")),
            unmodifiableList(asList("* ", "కాని "))
        ));

        dialects.put("th", new GherkinDialect(
            "th",
            "Thai",
            "ไทย",
            unmodifiableList(asList("โครงหลัก", "ความต้องการทางธุรกิจ", "ความสามารถ")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("เหตุการณ์")),
            unmodifiableList(asList("สรุปเหตุการณ์", "โครงสร้างของเหตุการณ์")),
            unmodifiableList(asList("แนวคิด")),
            unmodifiableList(asList("ชุดของตัวอย่าง", "ชุดของเหตุการณ์")),
            unmodifiableList(asList("* ", "กำหนดให้ ")),
            unmodifiableList(asList("* ", "เมื่อ ")),
            unmodifiableList(asList("* ", "ดังนั้น ")),
            unmodifiableList(asList("* ", "และ ")),
            unmodifiableList(asList("* ", "แต่ "))
        ));

        dialects.put("tlh", new GherkinDialect(
            "tlh",
            "Klingon",
            "tlhIngan",
            unmodifiableList(asList("Qap", "Qu'meH 'ut", "perbogh", "poQbogh malja'", "laH")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("lut")),
            unmodifiableList(asList("lut chovnatlh")),
            unmodifiableList(asList("mo'")),
            unmodifiableList(asList("ghantoH", "lutmey")),
            unmodifiableList(asList("* ", "ghu' noblu' ", "DaH ghu' bejlu' ")),
            unmodifiableList(asList("* ", "qaSDI' ")),
            unmodifiableList(asList("* ", "vaj ")),
            unmodifiableList(asList("* ", "'ej ", "latlh ")),
            unmodifiableList(asList("* ", "'ach ", "'a "))
        ));

        dialects.put("tr", new GherkinDialect(
            "tr",
            "Turkish",
            "Türkçe",
            unmodifiableList(asList("Özellik")),
            unmodifiableList(asList("Kural")),
            unmodifiableList(asList("Örnek", "Senaryo")),
            unmodifiableList(asList("Senaryo taslağı")),
            unmodifiableList(asList("Geçmiş")),
            unmodifiableList(asList("Örnekler")),
            unmodifiableList(asList("* ", "Diyelim ki ")),
            unmodifiableList(asList("* ", "Eğer ki ")),
            unmodifiableList(asList("* ", "O zaman ")),
            unmodifiableList(asList("* ", "Ve ")),
            unmodifiableList(asList("* ", "Fakat ", "Ama "))
        ));

        dialects.put("tt", new GherkinDialect(
            "tt",
            "Tatar",
            "Татарча",
            unmodifiableList(asList("Мөмкинлек", "Үзенчәлеклелек")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Сценарий")),
            unmodifiableList(asList("Сценарийның төзелеше")),
            unmodifiableList(asList("Кереш")),
            unmodifiableList(asList("Үрнәкләр", "Мисаллар")),
            unmodifiableList(asList("* ", "Әйтик ")),
            unmodifiableList(asList("* ", "Әгәр ")),
            unmodifiableList(asList("* ", "Нәтиҗәдә ")),
            unmodifiableList(asList("* ", "Һәм ", "Вә ")),
            unmodifiableList(asList("* ", "Ләкин ", "Әмма "))
        ));

        dialects.put("uk", new GherkinDialect(
            "uk",
            "Ukrainian",
            "Українська",
            unmodifiableList(asList("Функціонал")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Приклад", "Сценарій")),
            unmodifiableList(asList("Структура сценарію")),
            unmodifiableList(asList("Передумова")),
            unmodifiableList(asList("Приклади")),
            unmodifiableList(asList("* ", "Припустимо ", "Припустимо, що ", "Нехай ", "Дано ")),
            unmodifiableList(asList("* ", "Якщо ", "Коли ")),
            unmodifiableList(asList("* ", "То ", "Тоді ")),
            unmodifiableList(asList("* ", "І ", "А також ", "Та ")),
            unmodifiableList(asList("* ", "Але "))
        ));

        dialects.put("ur", new GherkinDialect(
            "ur",
            "Urdu",
            "اردو",
            unmodifiableList(asList("صلاحیت", "کاروبار کی ضرورت", "خصوصیت")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("منظرنامہ")),
            unmodifiableList(asList("منظر نامے کا خاکہ")),
            unmodifiableList(asList("پس منظر")),
            unmodifiableList(asList("مثالیں")),
            unmodifiableList(asList("* ", "اگر ", "بالفرض ", "فرض کیا ")),
            unmodifiableList(asList("* ", "جب ")),
            unmodifiableList(asList("* ", "پھر ", "تب ")),
            unmodifiableList(asList("* ", "اور ")),
            unmodifiableList(asList("* ", "لیکن "))
        ));

        dialects.put("uz", new GherkinDialect(
            "uz",
            "Uzbek",
            "Узбекча",
            unmodifiableList(asList("Функционал")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("Сценарий")),
            unmodifiableList(asList("Сценарий структураси")),
            unmodifiableList(asList("Тарих")),
            unmodifiableList(asList("Мисоллар")),
            unmodifiableList(asList("* ", "Belgilangan ")),
            unmodifiableList(asList("* ", "Агар ")),
            unmodifiableList(asList("* ", "Унда ")),
            unmodifiableList(asList("* ", "Ва ")),
            unmodifiableList(asList("* ", "Лекин ", "Бирок ", "Аммо "))
        ));

        dialects.put("vi", new GherkinDialect(
            "vi",
            "Vietnamese",
            "Tiếng Việt",
            unmodifiableList(asList("Tính năng")),
            unmodifiableList(asList("Quy tắc")),
            unmodifiableList(asList("Tình huống", "Kịch bản")),
            unmodifiableList(asList("Khung tình huống", "Khung kịch bản")),
            unmodifiableList(asList("Bối cảnh")),
            unmodifiableList(asList("Dữ liệu")),
            unmodifiableList(asList("* ", "Biết ", "Cho ")),
            unmodifiableList(asList("* ", "Khi ")),
            unmodifiableList(asList("* ", "Thì ")),
            unmodifiableList(asList("* ", "Và ")),
            unmodifiableList(asList("* ", "Nhưng "))
        ));

        dialects.put("zh-CN", new GherkinDialect(
            "zh-CN",
            "Chinese simplified",
            "简体中文",
            unmodifiableList(asList("功能")),
            unmodifiableList(asList("Rule", "规则")),
            unmodifiableList(asList("场景", "剧本")),
            unmodifiableList(asList("场景大纲", "剧本大纲")),
            unmodifiableList(asList("背景")),
            unmodifiableList(asList("例子")),
            unmodifiableList(asList("* ", "假如", "假设", "假定")),
            unmodifiableList(asList("* ", "当")),
            unmodifiableList(asList("* ", "那么")),
            unmodifiableList(asList("* ", "而且", "并且", "同时")),
            unmodifiableList(asList("* ", "但是"))
        ));

        dialects.put("zh-TW", new GherkinDialect(
            "zh-TW",
            "Chinese traditional",
            "繁體中文",
            unmodifiableList(asList("功能")),
            unmodifiableList(asList("Rule")),
            unmodifiableList(asList("場景", "劇本")),
            unmodifiableList(asList("場景大綱", "劇本大綱")),
            unmodifiableList(asList("背景")),
            unmodifiableList(asList("例子")),
            unmodifiableList(asList("* ", "假如", "假設", "假定")),
            unmodifiableList(asList("* ", "當")),
            unmodifiableList(asList("* ", "那麼")),
            unmodifiableList(asList("* ", "而且", "並且", "同時")),
            unmodifiableList(asList("* ", "但是"))
        ));

        DIALECTS = unmodifiableMap(dialects);
    }
}
