package com.vizzion.tvaberta;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Gathers the free channels from several public lists, keeps each list cached for 12 hours, and
// sorts them into categories:
//  - iptv-org: every Brazilian channel and every channel in Portuguese (Brazil, Portugal, Angola...);
//  - iptv-com and Free-TV: more Brazilian (and Portuguese) free-to-air channels;
//  - Pluto TV Brasil: its whole line-up (films, series, novelas, anime, kids...), straight from Pluto
//    with this device's own session (Pluto.java);
//  - M3UPT: Portugal's TV channels;
//  - and, as before, the big international names from iptv-org's category lists.
// Unofficial re-streams of pay TV are always left out.
final class Channels {

    // Category 0 is the viewer's favourites, kept on the device.
    static final int FAVORITES = 0, BRASIL = 1, NEWS = 2, SPORTS = 3, MOVIES = 4, SERIES = 5, NOVELAS = 6, KIDS = 7,
            ANIME = 8, DOCS = 9, ENTERTAINMENT = 10, MUSIC = 11, PORTUGAL = 12, WORLD = 13, ALL = 14;
    static final String[] CATEGORY_NAMES = {"★ Favoritos", "Brasil", "Notícias", "Esportes", "Filmes", "Séries", "Novelas",
            "Infantil", "Anime", "Documentários", "Entretenimento", "Música", "Portugal", "Mundo", "Todos"};
    private static final String BASE = "https://iptv-org.github.io/iptv/";
    // iptv-org's own category list for a category, for the famous international channels in it.
    private static final String[] CATEGORY_PATHS = {null, null, "categories/news", "categories/sports",
            "categories/movies", "categories/series", null, "categories/kids", null, "categories/documentary",
            "categories/entertainment", "categories/music", null, null, null};
    private static final long CACHE_MS = 12L * 60 * 60 * 1000;

    static final class Channel {
        String name, url, group, referrer, userAgent, search, logo, id;
        boolean partTime; // marked [Not 24/7] in the list (the tag itself is taken out of the name)
        int genre;        // one of the categories above (0 when it has none), set by pool()
        String country;   // "br", "pt", ... from the list's channel id; "pluto" for Pluto TV
        boolean manual;   // added by hand (YOUTUBE or MANUAIS): listed right after the famous channels

        // One line per channel for storage: fields split by tabs (tabs and line breaks removed first).
        String encode() {
            StringBuilder b = new StringBuilder();
            for (String s : new String[] {name, url, group, referrer, userAgent, logo}) {
                if (b.length() > 0) b.append('\t');
                b.append(s == null ? "" : s.replace('\t', ' ').replace('\n', ' '));
            }
            return b.toString();
        }

        static Channel decode(String line) {
            String[] f = line.split("\t", -1);
            if (f.length < 2 || f[1].isEmpty()) return null;
            Channel c = new Channel();
            c.name = f[0];
            c.url = f[1];
            c.group = f.length > 2 && !f[2].isEmpty() ? f[2] : null;
            c.referrer = f.length > 3 && !f[3].isEmpty() ? f[3] : null;
            c.userAgent = f.length > 4 && !f[4].isEmpty() ? f[4] : null;
            c.logo = f.length > 5 && !f[5].isEmpty() ? f[5] : null;
            c.search = fold(c.name);
            return c;
        }

        Map<String, String> headers() {
            Map<String, String> h = new HashMap<>();
            if (referrer != null && !referrer.isEmpty()) h.put("Referer", referrer);
            if (userAgent != null && !userAgent.isEmpty()) h.put("User-Agent", userAgent);
            return h;
        }
    }

    // The channel list the player walks through with up/down. Same process, so a static is enough.
    static List<Channel> playing = new ArrayList<>();

    // Loads a category. Throws only if no list at all could be had (offline and nothing cached).
    static List<Channel> load(Context c, int category) throws Exception {
        if (category == FAVORITES) return refreshed(c, favorites(c));
        List<Channel> pool = pool(c);
        List<Channel> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Channel ch : pool) {
            boolean in = category == ALL
                    || (category == BRASIL && "br".equals(ch.country))
                    || (category == PORTUGAL && "pt".equals(ch.country))
                    || (category == WORLD && "world".equals(ch.country))
                    || ch.genre == category;
            if (in && seen.add(key(ch))) out.add(ch);
        }
        // The big international names from iptv-org's own category list (BBC, CNN International,
        // FIFA+, Red Bull TV...), only the famous ones: these lists are mostly foreign channels.
        String path = CATEGORY_PATHS[category];
        if (path != null) {
            try {
                for (Channel ch : loadList(c, BASE + path + ".m3u", "cat_" + path.replace('/', '_') + ".m3u")) {
                    if (unofficial(ch) || !famous(ch) || isStalePluto(ch)) continue;
                    // Pluto channels come from Pluto's own line-up (in the pool), with working links.
                    if (ch.search.startsWith("pluto tv") || (ch.url != null && ch.url.contains("pluto.tv"))) continue;
                    ch.group = groupPt(ch.group);
                    officialCaze(ch);
                    if (seen.add(key(ch)) && seen.add(fold(ch.name))) out.add(ch);
                }
            } catch (Exception e) {
                if (out.isEmpty()) throw e;
            }
        }
        // CazéTV, which the lists only carry as a re-stream, becomes one entry: its official live.
        boolean hasCaze = false;
        for (java.util.Iterator<Channel> it = out.iterator(); it.hasNext(); ) {
            Channel ch = it.next();
            if (isCaze(ch)) { if (hasCaze) it.remove(); hasCaze = true; }
        }
        rank(out);
        addGlobo(category, out);
        return out;
    }

    // ---- The pool: every free channel in Portuguese, from all the lists ----

    private static final String IPTVORG_BR = BASE + "countries/br.m3u", IPTVORG_POR = BASE + "languages/por.m3u";
    private static final String FREE_TV = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8";
    private static final String M3UPT = "https://raw.githubusercontent.com/LITUATUI/M3UPT/main/M3U/M3UPT.m3u";

    private static volatile List<Channel> pool;
    private static volatile long poolAt;

    // All the channels, built from the cached lists (each list is downloaded again after 12 hours).
    // Kept in memory for 10 minutes, so moving between categories is instant.
    static synchronized List<Channel> pool(Context c) throws Exception {
        if (pool != null && System.currentTimeMillis() - poolAt < 10 * 60 * 1000L) return pool;
        List<Channel> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        Exception last = null;
        int lists = 0;
        // Pluto first, straight from Pluto with this device's own session (see Pluto.java). The other
        // lists' copies of Pluto channels are left out when this works: their links don't play.
        List<Channel> pluto = null;
        try { pluto = Pluto.channels(c); } catch (Exception e) { last = e; }
        if (pluto != null) {
            lists++;
            for (Channel ch : pluto) {
                ch.country = "pluto";
                ch.genre = plutoGenre(ch);
                add(out, seen, ch);
            }
        }
        addYouTube(out, seen); // before the lists, so CazéTV's re-streams there count as repeats
        addManual(out, seen);
        String[][] lists2 = {{IPTVORG_POR, "por.m3u"}, {IPTVORG_BR, "cat_countries_br.m3u"}, {EXTRA_BRASIL, "extra_brazil.m3u"},
                {FREE_TV, "free_tv.m3u"}, {M3UPT, "m3upt.m3u"}};
        for (String[] l : lists2) {
            List<Channel> list;
            try { list = loadList(c, l[0], l[1]); lists++; }
            catch (Exception e) { last = e; continue; }
            boolean freeTv = l[0].equals(FREE_TV), m3upt = l[0].equals(M3UPT), iptvCom = l[0].equals(EXTRA_BRASIL);
            for (Channel ch : list) {
                String g = ch.group == null ? "" : ch.group;
                if (freeTv) {
                    if (g.startsWith("Brazil")) ch.country = "br";
                    else if (g.startsWith("Portugal")) ch.country = "pt";
                    else continue; // the rest of the world: not in Portuguese
                } else if (m3upt) {
                    if (!g.equals("TV")) continue; // beach cams, radios, on-demand videos
                    // Its "TV" group also has channels from France, Spain, the US, Korea...: those go to Mundo
                    // (BBC, France 24... also to Notícias), Brazil's to Brasil; no code at all means Portugal.
                    String cc = countryOf(ch);
                    ch.country = cc == null || cc.equals("pt") ? "pt" : cc.equals("br") ? "br" : "world";
                } else {
                    ch.country = iptvCom ? "br" : countryOf(ch);
                }
                if (unofficial(ch) || isStalePluto(ch)) continue;
                // With Pluto's own line-up loaded, the other lists' copies of Pluto channels are only repeats.
                if (pluto != null && (ch.search.startsWith("pluto tv") || (ch.url != null && ch.url.contains("pluto.tv")))) continue;
                ch.genre = genreOf(ch);
                ch.group = groupPt(ch.group);
                officialCaze(ch);
                add(out, seen, ch);
            }
        }
        if (lists == 0) throw last != null ? last : new Exception("sem listas");
        pool = out;
        poolAt = System.currentTimeMillis();
        return out;
    }

    // ==== CANAIS ADICIONADOS À MÃO ====================================================================
    //
    // Duas listas para editar: YOUTUBE (canais do YouTube) e MANUAIS (links diretos .m3u8).
    // Cada canal é uma linha {"campo", "campo", ...}, com aspas em cada campo, vírgula entre eles e
    // vírgula no fim da linha. A categoria é o nome de uma das categorias do app, escrito igual:
    //   Brasil, Notícias, Esportes, Filmes, Séries, Novelas, Infantil, Anime, Documentários,
    //   Entretenimento, Música, Portugal, Mundo
    // (todo canal também aparece em "Todos"; os de Brasil, Portugal e Mundo ficam só nessas e em Todos).
    // Depois de editar: ./build.sh e instalar o out/tvaberta.apk.
    // Só canais gratuitos e oficiais: retransmissão de canal pago (pirata) não entra no app.

    // ---- YOUTUBE: canais que transmitem ao vivo no YouTube --------------------------------------------
    // {"Nome no app", "@ do canal sem o @", "Categoria", "código da foto"}
    //   - O @ é o que vem depois de youtube.com/@ no endereço do canal.
    //   - O código da foto: botão direito na foto do canal > "Copiar endereço da imagem"; use só o meio,
    //     sem "https://yt3.googleusercontent.com/" no começo e sem o "=s..." do final. Ou "" (sem logo).
    //   - Os de Esportes e Notícias também aparecem em Brasil.
    // OK abre a live atual no app do YouTube (ou a página do canal, se não estiver ao vivo); os que estão
    // ao vivo sobem para o topo com "● AO VIVO" (veja MainActivity).
    private static final String YT_IMG = "https://yt3.googleusercontent.com/";
    private static final String[][] YOUTUBE = {
            {"CazéTV", "CazeTV", "Esportes", "Zm3t3pgNIYY1HgkWYaWmU3E--oljMiWqX_2Ig4zkxziTkP3VOR9TGUmgJAPu7XrhP06ebxDg"},
            {"Canal GOAT", "canalgoatbr", "Esportes", "NtXade9YlBhuNpJu5Tcj-PRXH57GtNbON4e6KgaGhZnuqKPDvrhUGcQl88j7F72BKLAaZ72QEow"},
            {"Xsports", "xsports.brasil", "Esportes", "CTQyaT5nUAWjH0Vb-WIOrecZ3f4mAlS1N4niAFtIAX-g8gpTxITlkOY-dnRyh3th_1HxJ7KE"},
            {"N Sports", "nsports", "Esportes", "tt9WaJh3uawWJUv4upgjMNJgirABgKgaionIiPMTor-5rL5EnszV6sL8KDLOwXmqUZmKfnlu"},
            {"SportyNet", "SportyNetBrasil", "Esportes", "gljY0R_KSQ6j9HLIgFWayZnekdpxPfCH-aKmm_2OFV5nru3CuGMtPTL8cG0qliPcM65gtTPb"},
            {"TNT Sports Brasil", "TNTSportsBR", "Esportes", "VQ2IGEcrhMSpuFb3RFDQOsVV9xI0shwQVorL8VLUGUGaV61Va1UMo4iwFOBVfOsrwTTYET22Hdk"},
            {"Time Brasil TV", "timebrasil", "Esportes", "bY8s9kltdXLl_ZBnEFtR79YgZeRZ7U2ZuqMWllczYi4vNX9U6WqpNcrfbIaiOCRBmddOlHgrFQ0"},
            {"Paulistão", "paulistao", "Esportes", "ivecqYD8VCxv432H9vDhzLPeJgorSXGs7dke0o6ku2PCqyQFQzBZnW4qizS6UFIg7ICej995DQ"},
            {"Desimpedidos", "desimpedidos", "Esportes", "XL-Cyyvy-Qio3xasV0I1WCy3Qqps9nv0J0c16iXwmCmctXRUACDz3ZqCEgf_sOTUICkNXd9M"},
            {"CBF TV", "brasil", "Esportes", "wjJRTNty8R-pR-gjyqP9bejfxUsTT4GHg98tOt7sLsdJCJ8z_JWLnPzxtO9fSy-Qs4uc5y4U"},
            {"Kings League Brasil", "KingsLeagueBrazil", "Esportes", "iU7THtZxCokNyjkh17x_4zQjN_8BlLwQXoLkfDqYzwlAfpbGOvZG_3gtEH8sLuRgYEuX4sw22UA"},
            {"Jovem Pan Esportes", "jovempanesportes", "Esportes", "oBgJPP0LOnImgTAk6TzhGdb-cENNNjuzdedazT6VGVZtagNc1pTmUZgw82pf_y3s73vCJ4-2Lg"},
            {"Jovem Pan News no YouTube", "jovempannews", "Notícias", "ceWsIRiUn75sLwoPwHZgsIzwmjBJd_SzaHkyS3h1pXZ3hDQ6orkYvm8UL66yaF7SQdRpGHZGWQ"},
            {"CNN Brasil no YouTube", "CNNbrasil", "Notícias", "TUNo33rJfSv06L2UyQWsOPUMJglHptsHR2mFZOshcXL45xRJ1YgpRZWpb76IChbSBwyG1TBUvA"},
            {"SBT News no YouTube", "SBTNews", "Notícias", "3_uPF3ufjORBgR9Ii_4XoSw92CeIWI2FabiuH-kXT321ykNrWC0bmpdsmm7bdzlynDiX3Qx2ZQ"},
            {"Record News no YouTube", "recordnews", "Notícias", "HCLS0suaZ1ZqXvIzGsxElF0RKkonHsd7LqMsgJOoJhtHcrj2sS61NYSciv7okzmIQ5Fj9gRyZA"},
            {"Band Jornalismo", "bandjornalismo", "Notícias", "qqad5hNhAtWZblXQwSFFki_sqD0xied7pUw3con6MAsAenTbt0ZF6r0lqP3BCl8ZWQw_PyyOKA"},
            {"UOL", "UOL", "Notícias", "ukOUdTx4uhpLFu-xVSb2XIxRW22eljzVyEG-4X010tgxuQR9I8LvA1bzO-s6NlOUj6Z9FRPHWs8"},
            // TV aberta: emissoras nacionais, públicas, legislativas, religiosas e regionais ("no YouTube" no
            // nome, para não esconder a versão que toca direto no app, quando existe)
            {"SBT no YouTube", "sbt", "Brasil", "eHls-ZIV9thSqxHSakoKW2hA75Yr66dI5pbQwvH23KnzxUPqpDRNGFbKiQAercykSLvBZfMKvw"},
            {"TV Gazeta no YouTube", "tvgazetaoficial", "Brasil", "i90ULOcaX1z7rixngEuJCVNC0vEKO_ny5Ji1SlqDLH9g465ki6TyjFxK8kkFhNmoIAJImdynvA"},
            {"Record no YouTube", "SigaRecord", "Brasil", "9GrjdnZYbKM6F4uWXI8aM6D-7o9AsqCMnR0x-k8auFI_44JXGA6uYpHzpDRhbweKiTEFwYcsTQ"},
            {"RedeTV! no YouTube", "redetv", "Brasil", "CTFU2P7tW43punwV3olirWyw_I5tKS1YzMe6JfeBmWw09ipCy_6GW2uE9EjX8T63pgLIsiJdBpA"},
            {"TV Brasil no YouTube", "tvbrasil", "Brasil", "vUaFvTYgEV4KCQlcURn_zb7G1Js5ul9dXFatiPN-0sJVAYb52_nyGlXzuloVQ8VWy-Hm97dk7w"},
            {"TV Cultura no YouTube", "TVCultura", "Brasil", "qYagKsPfABygo6iadsT5w03ix8jqNd7Q8taB9TsvyEcvsSz5fg9WmTKBdgtW49bL6jEq-BjX1g"},
            {"Canal Futura no YouTube", "CanalfuturaOrgBr", "Brasil", "kr0tKdAtXTmwLbLzNqviYsXpta4SdHgvAkkGkVXJEDhoiZm0RxGKi_tcWolnwqc5gYjbREWIDck"},
            {"TV Aparecida no YouTube", "tvaparecida", "Brasil", "NGqUy8VulNmyLJaFv1O3KkeT_d9iswymNDdwZGlN7vxD_8Ov6wr3pgwrUoAW5Z8C8dfgArvg"},
            {"Rede Vida no YouTube", "tvredevida.aovivo", "Brasil", "Lj9O8OlU8uqjU0-5LDVZS85KKhwGEQk1IjvN5u_YXRAwKOd3FA2or8OzyR4lhVZqPNIZDV1DfdI"},
            {"Canção Nova no YouTube", "cancaonova", "Brasil", "57iHbMmPb0hkCV6EZgkQcrVteNhzLlzgfjwYzs4DQaSkqBKlTsROL5bwEWXUNaKCFMHHlG5P"},
            {"TV Evangelizar no YouTube", "TVEvangelizarOficial", "Brasil", "u2fY7cUohDjKxtjmPs-MUuiYV47CbsGFTpc5ksFJdmvEwOQ8ebcrve7jG4lmme0cjOYb5dYO7g"},
            {"TV Pai Eterno no YouTube", "paieterno", "Brasil", "6L4uoS7Oeku7rtBOYgxOWypGDGBeVpgpMa_iUzehUlVxzTTtfjXhKHNL6dArnvlhleCylcze"},
            {"Rede Família no YouTube", "rftvoficial", "Brasil", "NUiKrLGkYAuYo85xxWp8WVQ34aZYSS-nqi7vgY2BOjDWj9iF8BU9bb9340i-8zFSefMSYzh_Lg"},
            {"TV Novo Tempo no YouTube", "novotempo", "Brasil", "T5HrAc6yi7Rq863HPNcmpZrV-v2LpUadeDLdJiQ1XRs6q_3gWqDsegZiqNbZsvqo1_aaH-jGE2g"},
            {"Boa Vontade TV no YouTube", "boavontadetv", "Brasil", "JPz6qI3H0oUQInegkvou_rEmxRgpEPC_5oLjjbngfGZZqgdJWnNn3NOwwDSck6wk41TVQG1p_-U"},
            {"RIT TV no YouTube", "rittv-oficial", "Brasil", "9m_IokdvZMom77kdaSInqaTZG5yDif_sBj464-321RUztV6K8gcrGXT6yGRUuTAT-rZA1eddfPE"},
            {"Rede Gospel no YouTube", "RedeGospelTvOficial", "Brasil", "a2huRRsi49uU-R9MjIc_LiS7j7_r29b3S_iF5iGitJ_Iz8Y1sSM4tS8xTOO-N7F7yJrc5TIGbQ"},
            {"TV Mundo Maior no YouTube", "tvmundomaior", "Brasil", "kbl5O60qEaMk6_Edz1-GP5j4RVKsuz5V0tT6TMCd2mp7kc_rII2ZAJSyiSq0x7C_o2N59J72YQ"},
            {"Rede Século 21 no YouTube", "RedeSeculo21", "Brasil", "7pxacWyDWrc0dQ-TudEoMmazfk_Aj8Zto3i9a1m_LeVFkXx0EgVKkyUgcZdeml_Om0iifDeF"},
            {"Rede Nazaré no YouTube", "redenazare", "Brasil", "kt4jKrWRLdPykXvFmemAYaFBdNP3Bo3pvwDFHqx8m3X7dueiDT3L4C-DdCROGMi9hxCgvLtgSQ"},
            {"TV Justiça no YouTube", "RadioeTVJustica", "Notícias", "SaKGdQclMmYgSrIbC7njq32rrOV9PgGitfE1AMIJeI2eIg3Y5DogtGNkBXVACP9FRp0EsRcgJA"},
            {"TV Senado no YouTube", "tvsenado", "Notícias", "iVAmyh_VO7bzOOiR-kZL_uWLdX1cb5UJ8Pe1l1OCRiIvcT4GaYYg3_lz-fsSEn367kDILwtlig"},
            {"TV Câmara no YouTube", "camaradosdeputadosoficial", "Notícias", "tYMXSrVjYw9g19Nyq26yvD5kEE80zs-lX1WaQHj9-WS0p2gxGQBKpqKDURxMzJ9LaCCGvfsm"},
            {"TV Assembleia MG no YouTube", "assembleiamg", "Notícias", "ytc/AIdro_m6Kd4PAuPqKniofwiooa0VyOc9uaNCy9jVqgIfGkbbvpg"},
            {"Terraviva no YouTube", "CanalTerravivaYT", "Brasil", "9UoWE7YXPJULhR8hpreQvxGe2AoAtuq2FhnqAxWo1M4C1oeZjihVSXcrN848_PXxJvV19tTK0h0"},
            {"Canal Rural no YouTube", "CanalruralBr", "Brasil", "ytc/AIdro_mgFJqvM7PxDHsZ49udplGkvEBmVToxHol_yUf8CxtxTsw"},
            {"TVE Bahia no YouTube", "tvebahia", "Brasil", "T8CstwdtWlawoaa06fcA-vRU7Zsa72lEN8zmKbe9rjQk20qwsc8Y-eGeWWEjYNA39bP3xR0CaVo"},
            {"Rede Minas no YouTube", "redeminas", "Brasil", "cW2vMscjgd-likssTMx95b4OC2b_ZYSEnkVw4FST2hWvgayIa_wzjzfSrzmmYJnhAAbbbqmy"},
            {"TVE RS no YouTube", "tvepublicars", "Brasil", "28uOVcKyaIBD3zby07DdHkGfGkm1p0H51c3bA04v022pL3izK8wQlpeHfnt8R1saLobdRtXXLQ"},
            {"TV Brasil Central no YouTube", "tvbrasilcentral", "Brasil", "v90uWXHwm7VAE0C1I-gWNCe6MroovQ21f-u6IbwH1PPCPQ2VP16uV0V-kjF-jlVxfjaEHWad8A"},
            {"Meio Norte no YouTube", "meionortemais", "Brasil", "bDoJWbcBPg9nTQkC5tgpi-FSsxiKiitCE5KDKr8aaanMCfF4HcnLRsdC9yjPYW9daMQlAVJM"},
            {"TV Cidade Verde no YouTube", "TVCidadeVerdePI", "Brasil", "Kq_Q3PCddKsyGiiuPaLHy0fDg2TTueURhIusbWMwDAUBO1QlAp8wXR0xesZIzYIccoTnD-K-ow"},
            {"TV Aratu no YouTube", "TVAratuOficial", "Brasil", "qGfGBDHfAKdxt7J9d3pL-4mSEU0lCESlUyi5Dy9v1jYta8UcZu4li4opPQX68e6xC3hPTJ3MrQ"},
    };

    // ---- MANUAIS: links diretos de transmissão (.m3u8) ------------------------------------------------
    // {"Nome no app", "link da transmissão", "Categoria", "link do logo"}
    //   - O link é o endereço do vídeo ao vivo, normalmente terminando em .m3u8.
    //   - O logo é o endereço de uma imagem (.png/.jpg) na internet, ou "" (sem logo).
    // Exemplo (tire as // do começo para valer):
    //   {"Meu Canal", "https://exemplo.com/live/playlist.m3u8", "Entretenimento", "https://exemplo.com/logo.png"},
    // Como os outros canais, o app testa o link e esconde o canal enquanto ele estiver fora do ar.
    private static final String[][] MANUAIS = {
    };

    static boolean isYouTube(Channel ch) {
        return ch != null && ch.url != null && ch.url.startsWith("https://www.youtube.com/@");
    }

    // "Esportes" -> SPORTS; unknown names (a typo) -> Entretenimento, so the channel still shows up.
    private static int categoryNamed(String name) {
        String n = fold(name == null ? "" : name.trim());
        for (int i = 1; i < CATEGORY_NAMES.length; i++) if (fold(CATEGORY_NAMES[i]).equals(n)) return i;
        return ENTERTAINMENT;
    }

    // Puts a hand-added channel in its category: Brasil, Portugal and Mundo are "countries"; the rest
    // are genres (a YouTube channel of a genre also goes into Brasil).
    private static void place(Channel ch, String category, boolean youtube) {
        int cat = categoryNamed(category);
        ch.manual = true;
        if (cat == BRASIL) ch.country = "br";
        else if (cat == PORTUGAL) ch.country = "pt";
        else if (cat == WORLD) ch.country = "world";
        else { ch.genre = cat; if (youtube) ch.country = "br"; }
    }

    private static void addYouTube(List<Channel> out, java.util.Set<String> seen) {
        for (String[] y : YOUTUBE) {
            if (y.length < 3 || y[1].trim().isEmpty()) continue;
            Channel ch = new Channel();
            ch.name = y[0].trim();
            ch.search = fold(ch.name);
            ch.url = "https://www.youtube.com/@" + y[1].trim().replaceFirst("^@", "") + "/live";
            ch.group = "YouTube";
            ch.logo = y.length > 3 && !y[3].trim().isEmpty() ? YT_IMG + y[3].trim() + "=s176-c-k-c0x00ffffff-no-rj" : null;
            place(ch, y[2], true);
            if (seen.add(key(ch))) { seen.add(fold(ch.name)); out.add(ch); }
        }
    }

    private static void addManual(List<Channel> out, java.util.Set<String> seen) {
        for (String[] m : MANUAIS) {
            if (m.length < 2 || m[1].trim().isEmpty()) continue;
            Channel ch = new Channel();
            ch.name = m[0].trim();
            ch.search = fold(ch.name);
            ch.url = m[1].trim();
            ch.logo = m.length > 3 && !m[3].trim().isEmpty() ? m[3].trim() : null;
            place(ch, m.length > 2 ? m[2] : "", false);
            if (seen.add(key(ch))) { seen.add(fold(ch.name)); out.add(ch); }
        }
    }

    private static void add(List<Channel> out, java.util.Set<String> seen, Channel ch) {
        // The same stream, or the same channel name (with its quality), only once.
        if (!seen.add(key(ch)) || !seen.add(fold(ch.name))) return;
        out.add(ch);
    }

    // "SBTNacional.br@SD" -> "br".
    private static String countryOf(Channel ch) {
        if (ch.id == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\.([a-z]{2})(@|$)").matcher(ch.id);
        return m.find() ? m.group(1) : null;
    }

    // iptv-org's genres ("News;Sports", "Animation;Kids"...) to ours; 0 for General, Religious, etc.
    private static int genreOf(Channel ch) {
        if (ch.search.contains("novela")) return NOVELAS;
        if (ch.search.contains("anime")) return ANIME;
        String g = ch.group == null ? "" : ch.group.toLowerCase(Locale.ROOT);
        for (String part : g.split(";")) {
            switch (part.trim()) {
                case "news": case "business": return NEWS;
                case "sports": return SPORTS;
                case "movies": return MOVIES;
                case "series": return SERIES;
                case "kids": case "animation": return KIDS;
                case "documentary": case "science": case "travel": case "outdoor": return DOCS;
                case "music": return MUSIC;
                case "entertainment": case "comedy": case "lifestyle": case "family": case "culture":
                case "classic": case "cooking": case "auto": return ENTERTAINMENT;
                default: break;
            }
        }
        return 0;
    }

    // The lists' genre labels, shown beside each channel, in Portuguese ("News;Sports" -> "Notícias · Esportes").
    private static final Map<String, String> GROUP_PT = new HashMap<>();
    static {
        String[] pairs = {"news", "Notícias", "sports", "Esportes", "movies", "Filmes", "series", "Séries", "kids", "Infantil",
                "animation", "Animação", "documentary", "Documentários", "music", "Música", "entertainment", "Entretenimento",
                "general", "Geral", "religious", "Religioso", "legislative", "Legislativo", "education", "Educação",
                "lifestyle", "Estilo de vida", "comedy", "Comédia", "culture", "Cultura", "business", "Negócios",
                "family", "Família", "cooking", "Culinária", "travel", "Viagem", "science", "Ciência", "outdoor", "Natureza",
                "classic", "Clássicos", "weather", "Tempo", "shop", "Compras", "auto", "Carros", "relax", "Relaxar"};
        for (int i = 0; i < pairs.length; i += 2) GROUP_PT.put(pairs[i], pairs[i + 1]);
    }

    private static String groupPt(String group) {
        if (group == null) return null;
        StringBuilder b = new StringBuilder();
        for (String part : group.split(";")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (p.isEmpty() || p.equals("undefined") || p.equals("tv") || p.equals("brazil") || p.equals("portugal")) continue;
            String pt = GROUP_PT.get(p);
            if (b.length() > 0) b.append(';');
            b.append(pt != null ? pt : part.trim());
        }
        return b.length() == 0 ? null : b.toString();
    }

    // A channel's name without its quality or notes, to spot the same channel twice:
    // "Jovem Pan News (720p)" and "Jovem Pan NEWS" are both "jovem pan news".
    static String baseName(Channel ch) {
        return ch.search.replaceAll("\\(.*?\\)|\\[.*?\\]", " ").replaceAll("\\b(hd|sd|fhd|uhd|4k)\\b", " ")
                .replaceAll("\\s+", " ").trim();
    }

    // Pluto TV Brasil's own groups to our categories.
    private static int plutoGenre(Channel ch) {
        String g = ch.group == null ? "" : fold(ch.group);
        if (ch.search.contains("novela") || g.contains("novela")) return NOVELAS;
        if (g.contains("filme")) return MOVIES;
        if (g.contains("anime")) return ANIME;
        if (g.contains("infantil") || g.contains("nickelodeon") || g.contains("kids")) return KIDS;
        if (g.contains("esporte")) return SPORTS;
        if (g.contains("noticia")) return NEWS;
        if (g.contains("serie") || g.contains("jornada") || g.contains("south park")) return SERIES;
        if (g.contains("curiosidade") || g.contains("natureza") || g.contains("investiga") || g.contains("misterio")
                || g.contains("document")) return DOCS;
        if (g.contains("music") || g.contains("musica")) return MUSIC;
        return ENTERTAINMENT; // MTV, reality, comedy, retro, lifestyle...
    }

    // Pluto links other lists carry (directly, or through the jmp2.uk/plu-... redirect): they send
    // sound and picture apart, which Android's player shows as a black screen.
    private static boolean isStalePluto(Channel ch) {
        return ch.url != null && (ch.url.contains("pluto.tv") || ch.url.contains("jmp2.uk/plu-"));
    }

    // What identifies a channel over time: its link, except for Pluto TV, whose links change every day
    // (new token) while the channel's own id in them stays.
    private static final java.util.regex.Pattern PLUTO_ID = java.util.regex.Pattern.compile("/channel/([0-9a-f]{24})/");
    static String key(Channel ch) {
        if (ch.url != null && ch.url.contains("pluto.tv")) {
            java.util.regex.Matcher m = PLUTO_ID.matcher(ch.url);
            if (m.find()) return "pluto:" + m.group(1);
        }
        return ch.url;
    }

    // Favourites (and the last channel) saved with a Pluto link get today's link instead.
    static List<Channel> refreshed(Context c, List<Channel> list) {
        boolean any = false;
        for (Channel ch : list) if (key(ch).startsWith("pluto:")) any = true;
        if (!any) return list;
        java.util.Map<String, Channel> now = new java.util.HashMap<>();
        try { for (Channel ch : pool(c)) now.put(key(ch), ch); } catch (Exception e) { return list; }
        for (Channel ch : list) {
            Channel fresh = now.get(key(ch));
            if (fresh != null) { ch.url = fresh.url; ch.referrer = fresh.referrer; ch.userAgent = fresh.userAgent; }
        }
        return list;
    }

    // Globo, the most-watched channel, at the top of Brasil and Todos: it opens "Agora na TV" in the
    // Globoplay app (free with a Globo account), the official way to watch it live.
    static final String GLOBOPLAY = "https://globoplay.globo.com/agora-na-tv/";
    private static final String GLOBO_LOGO =
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c9/TV_Globo_2025.svg/330px-TV_Globo_2025.svg.png";

    private static void addGlobo(int category, List<Channel> list) {
        if (category != 1 && category != CATEGORY_NAMES.length - 1) return; // Brasil and Todos
        Channel g = new Channel();
        g.name = "Globo";
        g.search = fold(g.name);
        g.url = GLOBOPLAY;
        g.group = "Ao vivo no Globoplay";
        g.logo = GLOBO_LOGO;
        list.add(0, g);
    }

    static boolean isGloboplay(Channel ch) {
        return ch != null && GLOBOPLAY.equals(ch.url);
    }

    // Only the big names, most famous first (the lists carry no audience numbers, so this is chosen by
    // hand, at the level of the national networks). Matched on the accent-free, lower-case name.
    // Globo, ESPN, SporTV and other pay channels are not here: these lists only carry them as
    // unofficial re-streams (see PAY_TV); Globo is on the antenna and in Globoplay.
    private static final String[] FAME = {
        // Brazil's national free-to-air networks
        "^sbt nacional", "^record( \\(|$)", "^band( \\(|$)", "^rede ?tv!? ?(sd|mais)", "^tv cultura( \\(|$)",
        "^tv brasil( \\(|$)", "^tv gazeta( \\(|$)",
        // big news channels
        "^cnn brasil", "^jovem pan news", "^bandnews", "^record news", "^sbt news", "^pluto tv record news",
        // institutional: Congress and the courts
        "^tv camara (\\(brazil|2)", "^tv senado \\(brazil", "^tv justica",
        // sport, free and official
        "^caze", "^fifa\\+( \\(|$| portuguese)", "^red bull tv( \\(|$| br)", "^pluto tv esportes", "^ufc", "^wwe", "^nba( |$)",
        // kids
        "^nickelodeon (classico|teen|toons|\\(brazil)", "^nick jr\\. club.*\\bbr\\b", "^bob esponja", "^turma da monica",
        "^pluto tv kids( \\(|$)", "^pluto tv junior",
        // films and entertainment from Pluto TV Brasil
        "^pluto tv (cine|filmes)", "^mtv pluto tv br",
        // the big international news channels, main feeds in English or Portuguese
        "^bbc news( \\(|$| uk)", "^cnn international( \\(|$)", "^cnn portugal", "^dw english", "^france 24 english( \\(|$)",
        "^al jazeera english( \\(|$)"
    };
    private static final java.util.regex.Pattern[] FAME_P = new java.util.regex.Pattern[FAME.length];
    static {
        for (int i = 0; i < FAME.length; i++) FAME_P[i] = java.util.regex.Pattern.compile(FAME[i]);
    }

    // Feeds of those brands made for other countries (Spanish, German, Danish... versions of Pluto TV
    // and Nickelodeon), which a Brazilian viewer doesn't want.
    private static final java.util.regex.Pattern FOREIGN = java.util.regex.Pattern.compile(
            "\\b(latam|germany|dk|no|italy|spain|mexico|france)\\b|pantalones|juniorklubben|accion|clasico|crimen|de autor|"
            + "estelar|inspirador|romantico|suspenso|italiano|orientale");

    static boolean famous(Channel c) {
        if (fame(c) >= FAME_P.length || FOREIGN.matcher(c.search).find()) return false;
        // "(United States)" feeds only count in their Brazilian version (marked BR).
        return !c.search.contains("united states") || c.search.matches(".*\\bbr\\b.*");
    }

    private static int fame(Channel c) {
        for (int i = 0; i < FAME_P.length; i++) if (FAME_P[i].matcher(c.search).find()) return i;
        return FAME_P.length;
    }

    // Picture height from names like "(1080p)" or "(720i)"; 400 when the list doesn't say.
    private static int quality(Channel c) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((\\d{3,4})[pi]\\)").matcher(c.name);
        return m.find() ? Integer.parseInt(m.group(1)) : 400;
    }

    static void rank(List<Channel> list) {
        final java.util.Map<Channel, int[]> key = new java.util.IdentityHashMap<>();
        // Hand-added channels (YouTube and MANUAIS) come right after the famous ones.
        for (Channel c : list) key.put(c, new int[] {(c.manual || isYouTube(c)) && fame(c) == FAME_P.length ? FAME_P.length - 1 : fame(c),
                c.name.contains("Not 24/7") || c.partTime ? 1 : 0, -quality(c)});
        java.util.Collections.sort(list, (a, b) -> {
            int[] x = key.get(a), y = key.get(b);
            for (int i = 0; i < 3; i++) if (x[i] != y[i]) return Integer.compare(x[i], y[i]);
            return a.search.compareTo(b.search);
        });
    }

    private static final String EXTRA_BRASIL = "https://raw.githubusercontent.com/iptv-com/iptv/refs/heads/main/lists/brazil.m3u";
    // Brazilian pay-TV channels (and Globo's own feeds), which these lists only carry as unofficial
    // re-streams. Matched at the start of the name, so free channels like "TV Universal" or
    // "Gospel Cartoon" stay.
    private static final java.util.regex.Pattern PAY_TV = java.util.regex.Pattern.compile(
            "(?i)^(espn|sportv|premiere|telecine|hbo|combate|globonews|globoplay novelas|megapix|tnt|warner|band ?sports|"
            + "discovery|cartoon network|studio universal|fox|universal tv|rede globo|tv globo|globo|a&e|amc|axn|sony|"
            + "history|h2|nat ?geo|national geographic|tlc|gnt|multishow|viva|canal brasil|canal off|bis|gloob|gloobinho|"
            + "paramount|syfy|e!|lifetime|comedy central|cinemax|star channel|space|arte 1|film ?& ?arts|curta!|"
            + "prime box|tooncast|disney|nick jr\\.?$|discovery kids|investigation discovery|food network|hgtv|"
            + "animal planet|dazn|tv ?zyn|sbt ?sports|tnt sports|canal like|woohoo|music box brazil)\\b");

    // True for a Brazilian channel (tvg-id ".br") from that pay-TV list, or a pay channel's
    // "Latin America" feed: these lists only carry them as unofficial re-streams.
    // Whatever country code the list gives them (pay channels' "Latin America" feeds come marked as
    // other countries'). Pluto's own channels never come through here.
    static boolean unofficial(Channel ch) {
        return PAY_TV.matcher(ch.name).find() || PAY_TV_PT.matcher(ch.search).find()
                || ch.search.contains("latin america");
    }

    // Portugal's pay channels, as carried by iptv-org (M3UPT's own list only has official streams).
    private static final java.util.regex.Pattern PAY_TV_PT = java.util.regex.Pattern.compile(
            "^(sport ?tv|tvcine|hollywood|eleven|benfica tv|canal panda|biggs|cartoonito|nos studios|mov|canal q|eurosport)\\b");

    // One list: fresh cache, else download, else a stale cache. Throws if there is nothing at all.
    private static List<Channel> loadList(Context c, String url, String cacheName) throws Exception {
        File f = new File(c.getFilesDir(), cacheName);
        boolean fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < CACHE_MS;
        if (!fresh) {
            try {
                download(url, f);
            } catch (Exception e) {
                if (!f.exists()) throw e; // offline and never downloaded
            }
        }
        try (InputStream in = new FileInputStream(f)) {
            return parse(in);
        }
    }

    // Turns any CazéTV entry into the official one: its YouTube live page. Returns true if it was CazéTV.
    static boolean officialCaze(Channel ch) {
        if (ch == null || !ch.search.startsWith("caze")) return false;
        ch.name = "CazéTV";
        ch.search = fold(ch.name);
        ch.url = CazeLive.PAGE;
        ch.group = "Sports";
        ch.referrer = null;
        ch.userAgent = null;
        return true;
    }

    static boolean isCaze(Channel ch) {
        return ch != null && CazeLive.PAGE.equals(ch.url);
    }

    // ---- Favourites and the last channel watched, kept in the app's preferences ----

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("tvaberta", 0);
    }

    static List<Channel> favorites(Context c) {
        List<Channel> out = new ArrayList<>();
        for (String line : prefs(c).getString("favs", "").split("\n")) {
            Channel ch = line.isEmpty() ? null : Channel.decode(line);
            if (ch == null) continue;
            // Unofficial pay-TV re-streams saved by older versions never play (Pluto's own channels are fine).
            if (!key(ch).startsWith("pluto:") && PAY_TV.matcher(ch.name).find()) continue;
            ch.group = groupPt(ch.group); // saved by older versions with the lists' English labels
            officialCaze(ch);
            out.add(ch);
        }
        return out;
    }

    static boolean isFavorite(Context c, Channel ch) {
        String k = key(ch);
        for (Channel f : favorites(c)) if (key(f).equals(k)) return true;
        return false;
    }

    // Adds or removes a channel; returns true if it is a favourite now.
    static boolean toggleFavorite(Context c, Channel ch) {
        List<Channel> favs = favorites(c);
        boolean removed = false;
        String k = key(ch);
        for (java.util.Iterator<Channel> it = favs.iterator(); it.hasNext(); )
            if (key(it.next()).equals(k)) { it.remove(); removed = true; }
        if (!removed) favs.add(ch);
        StringBuilder b = new StringBuilder();
        for (Channel f : favs) b.append(f.encode()).append('\n');
        prefs(c).edit().putString("favs", b.toString()).apply();
        return !removed;
    }

    static void saveLast(Context c, Channel ch, int category) {
        prefs(c).edit().putString("last", ch.encode()).putInt("lastCategory", category).apply();
    }

    static Channel last(Context c) {
        String s = prefs(c).getString("last", null);
        Channel ch = s == null ? null : Channel.decode(s);
        officialCaze(ch);
        return ch;
    }

    static int lastCategory(Context c) {
        int i = prefs(c).getInt("lastCategory", 1);
        return i >= 0 && i < CATEGORY_NAMES.length ? i : 1;
    }

    // A channel logo, small: from the cache folder, else downloaded once and kept there. Null if none.
    static android.graphics.Bitmap logo(Context c, String url, int maxH) {
        if (url == null || url.isEmpty()) return null;
        File dir = new File(c.getCacheDir(), "logos");
        dir.mkdirs();
        File f = new File(dir, Integer.toHexString(url.hashCode()) + ".img");
        try {
            if (!f.exists()) {
                HttpURLConnection con = open(url, null);
                try {
                    if (con.getResponseCode() != 200) return null;
                    File tmp = new File(f.getPath() + ".tmp");
                    try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[8192];
                        int total = 0;
                        for (int n; (n = in.read(buf)) > 0; ) {
                            out.write(buf, 0, n);
                            if ((total += n) > 2_000_000) return null; // not a logo
                        }
                    }
                    tmp.renameTo(f);
                } finally {
                    con.disconnect();
                }
            }
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outHeight / (sample * 2) >= maxH) sample *= 2;
            o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            return android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Exception e) {
            return null;
        }
    }

    private static void download(String url, File to) throws Exception {
        HttpURLConnection con = open(url, null);
        try {
            if (con.getResponseCode() != 200) throw new Exception("HTTP " + con.getResponseCode());
            File tmp = new File(to.getPath() + ".tmp");
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            if (!tmp.renameTo(to)) { to.delete(); tmp.renameTo(to); }
        } finally {
            con.disconnect();
        }
    }

    // Follows redirects by hand (HttpURLConnection won't go from http to https on its own).
    static HttpURLConnection open(String url, Map<String, String> headers) throws Exception {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(10000);
            con.setInstanceFollowRedirects(false);
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet())
                con.setRequestProperty(e.getKey(), e.getValue());
            int code = con.getResponseCode();
            if (code >= 300 && code < 400 && con.getHeaderField("Location") != null) {
                url = new URL(new URL(url), con.getHeaderField("Location")).toString();
                con.disconnect();
                continue;
            }
            return con;
        }
        throw new Exception("too many redirects");
    }

    // True if the stream answers with something that isn't an error, within a few seconds.
    static boolean responds(Channel ch) {
        HttpURLConnection con = null;
        try {
            Map<String, String> h = ch.headers();
            h.put("Range", "bytes=0-2047");
            con = open(ch.url, h);
            con.setReadTimeout(6000);
            int code = con.getResponseCode();
            if (code >= 400) return false;
            try (InputStream in = con.getInputStream()) { return in.read(new byte[512]) > 0; }
        } catch (Exception e) {
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    // M3U: #EXTINF:-1 attrs,Name  then optional #EXTVLCOPT / #KODIPROP lines, then the URL.
    // Channels marked [Geo-blocked] or that need #KODIPROP (DRM, Kodi only) are left out.
    static List<Channel> parse(InputStream in) throws Exception {
        List<Channel> out = new ArrayList<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"), 16384);
        Channel cur = null;
        boolean skip = false;
        for (String line; (line = r.readLine()) != null; ) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                cur = new Channel();
                skip = false;
                cur.name = nameOf(line);
                cur.group = attr(line, "group-title");
                cur.logo = attr(line, "tvg-logo");
                cur.id = attr(line, "tvg-id");
                cur.referrer = attr(line, "http-referrer");
                cur.userAgent = attr(line, "http-user-agent");
                if (cur.name.contains("[Geo-blocked]")) skip = true;
            } else if (line.startsWith("#EXTVLCOPT:") && cur != null) {
                String opt = line.substring(11);
                if (opt.startsWith("http-referrer=")) cur.referrer = opt.substring(14);
                else if (opt.startsWith("http-user-agent=")) cur.userAgent = opt.substring(16);
            } else if (line.startsWith("#KODIPROP")) {
                skip = true;
            } else if (!line.startsWith("#") && cur != null) {
                if (!skip) {
                    cur.url = line;
                    cur.partTime = cur.name.contains("[Not 24/7]");
                    cur.name = cur.name.replace("[Not 24/7]", "").trim();
                    cur.search = fold(cur.name);
                    out.add(cur);
                }
                cur = null;
            }
        }
        return out;
    }

    // The name is after the first comma that isn't inside quotes.
    private static String nameOf(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') quoted = !quoted;
            else if (ch == ',' && !quoted) return line.substring(i + 1).trim();
        }
        return line;
    }

    private static String attr(String line, String key) {
        int i = line.indexOf(key + "=\"");
        if (i < 0) return null;
        int start = i + key.length() + 2, end = line.indexOf('"', start);
        return end < 0 ? null : line.substring(start, end);
    }

    // Lower case without accents, so "noticias" finds "Notícias".
    static String fold(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }
}
