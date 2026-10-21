package io.freemytv;

import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.Locale;

/** Official reference RUB price of one USD / EUR; publish only a complete validated response. */
final class CbrRates {
    final String date;
    final double[] values;
    private CbrRates(String date, double[] values) { this.date = date; this.values = values; }
    static CbrRates parse(String body) throws Exception {
        if (body.length() > 256_000 || body.contains("<!DOCTYPE") || body.contains("<!ENTITY"))
            throw new IllegalArgumentException("Invalid CBR XML");
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new StringReader(body));
        String date = null, code = null, nominal = null, value = null;
        double[] rates = {Double.NaN, Double.NaN};
        for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.START_TAG) {
                switch (p.getName()) {
                    case "ValCurs": date = p.getAttributeValue(null, "Date"); break;
                    case "Valute": code = nominal = value = null; break;
                    case "CharCode": code = p.nextText(); break;
                    case "Nominal": nominal = p.nextText(); break;
                    case "Value": value = p.nextText(); break;
                }
            } else if (e == XmlPullParser.END_TAG && "Valute".equals(p.getName())) {
                int i = "USD".equals(code) ? 0 : "EUR".equals(code) ? 1 : -1;
                if (i >= 0) {
                    double n = Double.parseDouble(nominal.trim().replace(',', '.'));
                    double v = Double.parseDouble(value.trim().replace(',', '.')) / n;
                    if (!(n > 0) || !(v > 0) || Double.isInfinite(v)) throw new IllegalArgumentException("Invalid CBR rate");
                    rates[i] = v;
                }
            }
        }
        if (date == null || !date.matches("\\d{2}\\.\\d{2}\\.\\d{4}") || Double.isNaN(rates[0]) || Double.isNaN(rates[1]))
            throw new IllegalArgumentException("Incomplete CBR response");
        SimpleDateFormat f = new SimpleDateFormat("dd.MM.yyyy", Locale.ROOT);
        f.setLenient(false); f.parse(date);
        return new CbrRates(date, rates);
    }
}
