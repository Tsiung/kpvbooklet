package com.github.chrox.kpvbooklet;

import java.io.*;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.Date;

import org.json.simple.JSONObject;
import org.json.simple.JSONArray;

import com.amazon.kindle.booklet.AbstractBooklet;

/**
 * A Booklet for starting kpdfviewer. 
 * 
 * Modified by chrox@github.
 * 
 * @author Patric Mueller &lt;bhaak@gmx.net&gt;
 */
public class KPVBooklet extends AbstractBooklet {

	//private static final PrintStream log = Log.INSTANCE;
	private final String kpdfviewer = "/mnt/us/kindlepdfviewer/kpdf.sh";
	
	private Process kpdfviewerProcess;
	private CCAdapter request = CCAdapter.INSTANCE;
	private static final PrintStream logger = Log.INSTANCE;
	
	public KPVBooklet() {
		log("KPVBooklet");
	}
	
	public void start(URI contentURI) {
		String path = contentURI.getPath();
		log("Opening " + path + " with kindlepdfviewer...");
		String[] cmd = new String[] {kpdfviewer, path};
		try {
			kpdfviewerProcess = Runtime.getRuntime().exec(cmd);
		} catch (IOException e) {
			log("E: " + e.toString());
		}
		
		Thread thread = new kpdfviewerWaitThread(path);
		thread.start();
	}

	public void stop() {
		log("stop()");
		// Stop kpdfviewer
		if (kpdfviewerProcess != null) {
			try {
				killQuitProcess(kpdfviewerProcess);
			} catch (Exception e) {
				log("E: " + e.toString());
			}
		}
		super.stop();
	}
	
	/** 
	 * Send a QUIT signal to a process.
	 * 
	 * See http://stackoverflow.com/questions/2950338/how-can-i-kill-a-linux-process-in-java-with-sigkill-process-destroy-does-sigte#answer-2951193
	 */
	private void killQuitProcess(Process process)
		throws InterruptedException, IOException, SecurityException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException {
		if (process.getClass().getName().equals("java.lang.UNIXProcess")) {
			Class cl = process.getClass();
			Field field = cl.getDeclaredField("pid");
			field.setAccessible(true);
			Object pidObject = field.get(process);

			Runtime.getRuntime().exec("kill -QUIT " + pidObject).waitFor();
		} else {
			throw new IllegalArgumentException("Needs to be a UNIXProcess");
		}
	}
	
	/** This thread waits for kpdfviewer to finish and then sends
	 * a BACKWARD lipc event. 
	 */
	class kpdfviewerWaitThread extends Thread {
		private String content_path = "";
		
		public kpdfviewerWaitThread(String path) {
			content_path = path;
		}
		public void run() {
			try {
				// wait for kpdfviewer to finish
				kpdfviewerProcess.waitFor();
			} catch (InterruptedException e) {
				log("E: " + e.toString());
			}
			
			// update content catlog after kpdfviewer exits
			updateCC(content_path, extractPercentFinished(content_path));
			
			// kill all kpdfviewer process
			try {
				Runtime.getRuntime().exec("killall reader.lua");
			} catch (IOException e) {
				log("E: " + e.toString());
			}
			
			// sent go home lipc event after kpdfviewer exits
			try {
				Runtime.getRuntime().exec("lipc-set-prop com.lab126.appmgrd start app://com.lab126.booklet.home");
			} catch (IOException e) {
				log("E: " + e.toString());
			}
		}
	}
	
	/**
	 * Update lastAccess and displayTag fields in ContentCatlog
	 * @param file path
	 */
	private void updateCC(String path, float percentFinished) {
		long lastAccess = new Date().getTime() / 1000L;
		int dot = path.lastIndexOf('.');
		String tag = (dot == -1) ? "" : path.substring(dot+1).toUpperCase();
		path = JSONObject.escape(path);
		String json_query = "{\"filter\":{\"Equals\":{\"value\":\"" + path + "\",\"path\":\"location\"}},\"type\":\"QueryRequest\",\"maxResults\":1,\"sortOrder\":[{\"order\":\"descending\",\"path\":\"lastAccess\"},{\"order\":\"ascending\",\"path\":\"titles[0].collation\"}],\"startIndex\":0,\"id\":1,\"resultType\":\"fast\"}";
		JSONObject json = request.perform("query", json_query);
		JSONArray values = (JSONArray) json.get("values");
		JSONObject value =(JSONObject) values.get(0);
		String uuid = (String) value.get("uuid");
		String json_change = "{\"commands\":[{\"update\":{\"uuid\":\"" + uuid + "\",\"lastAccess\":" + lastAccess + ",\"percentFinished\":" + percentFinished + ",\"displayTags\":[\"" + tag + "\"]" + "}}],\"type\":\"ChangeRequest\",\"id\":1}";
		request.perform("change", json_change);
		log("I: UpdateCC:file:" + path + ",lastAccess:" + lastAccess + ",percentFinished:" + percentFinished);
	}
	
	/**
	 * Extract last_percent in document history file
	 * @param file path
	 */
	private float extractPercentFinished(String path) {
		float percent_finished = 0.0f;
		String history_dir = "/mnt/us/kindlepdfviewer/history/";
		int slash = path.lastIndexOf('/');
		String parentname = (slash == -1) ? "" : path.substring(0, slash+1);
		String basename = (slash == -1) ? "" : path.substring(slash+1);
		String histroypath = history_dir + "[" + parentname.replace('/','#') + "] " + basename + ".lua";
		log("I: found history file: " + histroypath);
		try {
			BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(histroypath)));
			String line;
			while ((line = br.readLine()) != null) {
				if (line.indexOf("[\"percent_finished\"]") > -1) {
					log("I: found percent_finished line: " + line);
					int equal = line.lastIndexOf('=');
					int comma = line.lastIndexOf(',');
					if (equal != -1 && comma != -1) {
						String value = line.substring(equal+1, comma).trim();
						percent_finished = Float.parseFloat(value) * 100;
					}
				}
		    }
		    br.close();
		} catch (IOException e) {
			log("E: " + e.toString());
		}
		return percent_finished;
	}
	
	private void log(String msg) {
		logger.println(msg);
	}
}
