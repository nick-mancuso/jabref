package net.sf.jabref.imports;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sf.jabref.BibtexEntry;
import net.sf.jabref.BibtexFields;
import net.sf.jabref.Globals;
import net.sf.jabref.Util;
import net.sf.jabref.util.CaseChanger;

/**
 * Importer for the ISI Web of Science format.
 * 
 * Documentation about ISI WOS format:
 * 
 * <ul>
 * <li>http://wos.isitrial.com/help/helpprn.html</li>
 * </ul>
 * 
 * Todo:
 * <ul>
 * <li>Check compatibility with other ISI2Bib tools like:
 * http://www-lab.imr.tohoku.ac.jp/~t-nissie/computer/software/isi/ or
 * http://www.tug.org/tex-archive/biblio/bibtex/utils/isi2bibtex/isi2bibtex or
 * http://web.mit.edu/emilio/www/utils.html</li>
 * <li>Deal with capitalization correctly</li>
 * </ul>
 * 
 * @author $Author$
 * @version $Revision$ ($Date$)
 * 
 */
public class IsiImporter extends ImportFormat {
	/**
	 * Return the name of this import format.
	 */
	public String getFormatName() {
		return "ISI";
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see net.sf.jabref.imports.ImportFormat#getCLIId()
	 */
	public String getCLIId() {
		return "isi";
	}

	/**
	 * Check whether the source is in the correct format for this importer.
	 */
	public boolean isRecognizedFormat(InputStream stream) throws IOException {
		// Our strategy is to look for the "PY <year>" line.
		BufferedReader in = new BufferedReader(ImportFormatReader.getReaderDefaultEncoding(stream));
		Pattern pat1 = Pattern.compile("PY \\d{4}");

		// was PY \\\\d{4}? before
		String str;

		while ((str = in.readLine()) != null) {

			// The following line gives false positives for RIS files, so it
			// should
			// not be uncommented. The hypen is a characteristic of the RIS
			// format.
			// str = str.replace(" - ", "");

			if (pat1.matcher(str).find())
				return true;
		}

		return false;
	}

	static Pattern subsupPattern = Pattern.compile("/(sub|sup)\\s+(.*?)\\s*/");

	static public void processSubSup(HashMap map) {

		String[] subsup = { "title", "abstract", "review", "notes" };

		for (int i = 0; i < subsup.length; i++) {
			if (map.containsKey(subsup[i])) {

				Matcher m = subsupPattern.matcher((String) map.get(subsup[i]));
				StringBuffer sb = new StringBuffer();

				while (m.find()) {

					String group2 = m.group(2);
					if (group2.length() > 1) {
						group2 = "{" + group2 + "}";
					}
					if (m.group(1).equals("sub")) {
						m.appendReplacement(sb, "\\$_" + group2 + "\\$");
					} else {
						m.appendReplacement(sb, "\\$^" + group2 + "\\$");
					}
				}
				m.appendTail(sb);
				map.put(subsup[i], sb.toString());
			}
		}
	}

	static public void processCapitalization(HashMap map) {

		String[] subsup = { "title", "journal", "publisher" };

		for (int i = 0; i < subsup.length; i++) {

			if (map.containsKey(subsup[i])) {

				String s = (String) map.get(subsup[i]);

				if (s.toUpperCase().equals(s)) {
					s = CaseChanger.changeCase(s, CaseChanger.UPPER_EACH_FIRST);
					map.put(subsup[i], s);
				}
			}
		}
	}

	/**
	 * Parse the entries in the source, and return a List of BibtexEntry
	 * objects.
	 */
	public List importEntries(InputStream stream) throws IOException {
		ArrayList bibitems = new ArrayList();
		StringBuffer sb = new StringBuffer();

		BufferedReader in = new BufferedReader(ImportFormatReader.getReaderDefaultEncoding(stream));

		// Pattern fieldPattern = Pattern.compile("^AU |^TI |^SO |^DT |^C1 |^AB
		// |^ID |^BP |^PY |^SE |^PY |^VL |^IS ");
		String str;

		while ((str = in.readLine()) != null) {
			if (str.length() < 3)
				continue;

			// begining of a new item
			if (str.substring(0, 3).equals("PT "))
				sb.append("::").append(str);
			else {
				String beg = str.substring(0, 3).trim();

				// I could have used the fieldPattern regular expression instead
				// however this seems to be
				// quick and dirty and it works!
				if (beg.length() == 2) {
					sb.append(" ## "); // mark the begining of each field
					sb.append(str);
				} else {
					sb.append("EOLEOL"); // mark the end of each line
					sb.append(str.trim()); // remove the initial spaces
				}
			}
		}

		String[] entries = sb.toString().split("::");

		HashMap hm = new HashMap();

		// skip the first entry as it is either empty or has document header
		for (int i = 0; i < entries.length; i++) {
			String[] fields = entries[i].split(" ## ");

			if (fields.length == 0)
				fields = entries[i].split("\n");

			String Type = "";
			String PT = "";
			String pages = "";
			hm.clear();

			nextField: for (int j = 0; j < fields.length; j++) {
				// empty field don't do anything
				if (fields[j].length() <= 2)
					continue;

				// this is Java 1.5.0 code:
				// fields[j] = fields[j].replace(" - ", "");
				// TODO: switch to 1.5.0 some day; until then, use 1.4.2 code:
				fields[j] = fields[j].replaceAll(" - ", "");

				String beg = fields[j].substring(0, 2);
				String value = fields[j].substring(2).trim();

				if (beg.equals("PT")) {
					PT = value.replaceAll("Journal", "article").replaceAll("J", "article");
					Type = "article"; // make all of them PT?
				} else if (beg.equals("TY")) {
					if ("CONF".equals(value))
						Type = "inproceedings";
				} else if (beg.equals("JO"))
					hm.put("booktitle", value);
				else if (beg.equals("AU")) {
					String author = isiAuthorsConvert(value.replaceAll("EOLEOL", " and "));

					// if there is already someone there then append with "and"
					if (hm.get("author") != null)
						author = (String) hm.get("author") + " and " + author;

					hm.put("author", author);
				} else if (beg.equals("TI"))
					hm.put("title", value.replaceAll("EOLEOL", " "));
				else if (beg.equals("SO"))
					hm.put("journal", value.replaceAll("EOLEOL", " "));
				else if (beg.equals("ID"))
					hm.put("keywords", value.replaceAll("EOLEOL", " "));
				else if (beg.equals("AB"))
					hm.put("abstract", value.replaceAll("EOLEOL", " "));
				else if (beg.equals("BP") || beg.equals("BR") || beg.equals("SP"))
					pages = value;
				else if (beg.equals("EP")) {
					int detpos = value.indexOf(' ');

					// tweak for IEEE Explore
					if (detpos != -1)
						value = value.substring(0, detpos);

					pages = pages + "--" + value;
				} else if (beg.equals("AR"))
					pages = value;
				else if (beg.equals("IS"))
					hm.put("number", value);
				else if (beg.equals("PY"))
					hm.put("year", value);
				else if (beg.equals("VL"))
					hm.put("volume", value);
				else if (beg.equals("PU"))
					hm.put("publisher", value);
				else if (beg.equals("PD")) {

					String[] parts = value.split(" ");
					for (int ii = 0; ii < parts.length; ii++) {
						if (Globals.MONTH_STRINGS.containsKey(parts[ii].toLowerCase())) {
							hm.put("month", "#" + parts[ii].toLowerCase() + "#");
							continue nextField;
						}
					}

					// Try two digit month
					for (int ii = 0; ii < parts.length; ii++) {
						int number;
						try {
							number = Integer.parseInt(parts[ii]);
							if (number >= 1 && number <= 12) {
								hm.put("month", "#" + Globals.MONTHS[number - 1] + "#");
								continue nextField;
							}
						} catch (NumberFormatException e) {

						}
					}

				} else if (beg.equals("DT")) {
					Type = value;
					if (Type.equals("Review")) {
						Type = "article"; // set "Review" in Note/Comment?
					} else if (Type.startsWith("Article") || Type.startsWith("Journal")
						|| PT.equals("article")) {
						Type = "article";
						continue;
					} else {
						Type = "misc";
					}
				} else if (beg.equals("CR")) {
					hm.put("CitedReferences", value.replaceAll("EOLEOL", " ; ").trim());
				} else {
					// Preserve all other entries except
					if (beg.equals("ER") || beg.equals("EF") || beg.equals("VR")
						|| beg.equals("FN"))
						continue nextField;
					hm.put(beg, value);
				}
			}

			if (!"".equals(pages))
				hm.put("pages", pages);

			// Skip empty entries
			if (hm.size() == 0)
				continue;

			BibtexEntry b = new BibtexEntry(BibtexFields.DEFAULT_BIBTEXENTRY_ID, Globals
				.getEntryType(Type));
			// id assumes an existing database so don't

			// Remove empty fields:
			ArrayList toRemove = new ArrayList();
			for (Iterator it = hm.keySet().iterator(); it.hasNext();) {
				Object key = it.next();
				String content = (String) hm.get(key);
				if ((content == null) || (content.trim().length() == 0))
					toRemove.add(key);
			}
			for (Iterator iterator = toRemove.iterator(); iterator.hasNext();) {
				hm.remove(iterator.next());

			}

			// Polish entries
			processSubSup(hm);
			processCapitalization(hm);

			b.setField(hm);

			bibitems.add(b);
		}

		return bibitems;
	}

	/**
	 * Will expand ISI first names.
	 * 
	 * Fixed bug from:
	 * http://sourceforge.net/tracker/index.php?func=detail&aid=1542552&group_id=92314&atid=600306
	 * 
	 */
	public static String isiAuthorConvert(String author) {

		String[] s = author.split(",");
		if (s.length != 2)
			return author;

		String last = s[0].trim();
		String first = s[1].trim();

		first = first.replaceAll("\\.|\\s", "");

		StringBuffer sb = new StringBuffer();
		sb.append(last).append(", ");

		for (int i = 0; i < first.length(); i++) {
			sb.append(first.charAt(i)).append(".");

			if (i < first.length() - 1)
				sb.append(" ");
		}
		return sb.toString();
	}

	public static String[] isiAuthorsConvert(String[] authors) {

		String[] result = new String[authors.length];
		for (int i = 0; i < result.length; i++) {
			result[i] = isiAuthorConvert(authors[i]);
		}
		return result;
	}

	public static String isiAuthorsConvert(String authors) {
		String[] s = isiAuthorsConvert(authors.split(" and "));
		return Util.join(s, " and ", 0, s.length);
	}

}
