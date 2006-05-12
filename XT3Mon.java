/* $Id$ */

import java.applet.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

public class XT3Mon extends Applet implements Runnable {
	private final static int DT_NODE = 0;
	private final static int DT_JOB = 1;

	private final static int SLEEPINTV = 120000;

	private final static int NROWS = 2;
	private final static int NCABS = 11;
	private final static int NCAGES = 3;
	private final static int NMODS = 8;
	private final static int NNODES = 4;

	private State[] states = new State[] {
		new State("Free", Color.white),				/* ST_FREE */
		new State("Down (CPA)", Color.red),			/* ST_DOWN */
		new State("Disabled (PBS)", Color.gray),	/* ST_DISABLED */
		new State("Used", null),					/* ST_USED */
		new State("Service", Color.yellow),			/* ST_SVC */
	};

	public final static int ST_FREE = 0;
	public final static int ST_DOWN = 1;
	public final static int ST_DISABLED = 2;
	public final static int ST_USED = 3;
	public final static int ST_SVC = 4;

	private long lastrun;
	private Thread t;
	private int width;
	private int height;
	private Node[][][][][] nodes = new Node[NROWS][NCABS][NCAGES][NMODS][NNODES];
	private ArrayList invmap;
	private LinkedList jobs;
	private int njobs;
	private String node_url, job_url;

	public void start() {
		this.t = new Thread(this);
		this.t.start();
	}

	public void stop() {
		this.t = null;
	}

	public void run() {
		Thread me = Thread.currentThread();
		while (this.t == me) {
			try {
				Thread.currentThread().sleep(SLEEPINTV);
			} catch (Exception e) {
				System.err.println(e.toString());
			}
			repaint();
		}
	}

	public int[] parse_pos(String[] s) {
		int[] max = { NROWS, NCABS, NCAGES, NMODS, NNODES };
		int[] c = new int[max.length];

		for (int i = 0; i < c.length; i++) {
			c[i] = Integer.parseInt(s[i + 1]);
			if (c[i] >= max[i])
				return (null);
		}
		return (c);
	}

	public void load(int type) {
		String file, host, path;
		Socket s;

		file = null;
		switch (type) {
		case DT_NODE:
			file = this.node_url;
			break;
		case DT_JOB:
			file = this.job_url;
			break;
		}

		try {
			Matcher m = Pattern.compile("^http://([^/]+)(.*)$").matcher(file);
			if (!m.matches())
				throw new Exception("invalid URL");
			host = m.group(1);
			path = m.group(2);

			s = new Socket(host, 80);
			BufferedReader r = new BufferedReader(
			  new InputStreamReader(s.getInputStream()));

			PrintWriter out = new PrintWriter(s.getOutputStream(), true);
			out.println("GET " + path + " HTTP/1.1");
			out.println("Host: " + host);
			out.println("Connection: close");
			out.println("");

			String ln;
			do {
				ln = r.readLine();
			} while (ln != null && !ln.equals(""));

			switch (type) {
			case DT_NODE:
				load_node(r);
				break;
			case DT_JOB:
				load_job(r);
				break;
			}
			out.close();
			r.close();
		} catch (Exception e) {
			System.err.println(file + ": " + e);
		}
	}

	/*
	 * Format is:
	 * 0    1 2  3  4 5 6 7 8	9	10	11	12	13	14	 15
	 * nid	r cb cg m n x y z	st	en	jid	tmp	yid	fail lst
	 * =====================================================
	 * 23	0 0  0  5 3	0 3 5	c	1	0	29	0	0	 c
	 */
	public void load_node(BufferedReader r) throws Exception {
		int[] c;
		String l;
		int lineno;

		for (int i = 0; i < this.states.length; i++)
			this.states[i].nmemb = 0;

		lineno = 0;
		while ((l = r.readLine()) != null) {
			lineno++;
			l = l.replaceFirst("^\\s+", "");
			if (l.equals("") || l.charAt(0) == '#')
				continue;

			String[] s = l.split("\\s+");

			if (s.length != 16 || (c = parse_pos(s)) == null) {
				System.err.println("[node] malformed line " +
				  "(node:" + lineno + "): " + l);
				continue;
			}
			Node n = this.nodes[c[0]][c[1]][c[2]][c[3]][c[4]];
			n.nid = Integer.parseInt(s[0]);

			switch (s[9].charAt(0)) {
			case 'n':
				n.state = ST_DOWN;
				break;
			case 'i':
				n.state = ST_SVC;
				break;
			case 'c':
				n.state = ST_FREE;
				break;
			}

			if (s[10].equals("0"))
				n.state = ST_DISABLED;
			else if (!s[11].equals("0")) {
				n.state = ST_USED;
				n.job = job_get(this.jobs, s[11]);
			}

			if (n.state == ST_USED)
				n.job.nmemb++;
			else
				this.states[n.state].nmemb++;

			this.invmap.ensureCapacity(n.nid + 1);
			for (int k = this.invmap.size(); k <= n.nid; k++)
				this.invmap.add(null);
			this.invmap.set(n.nid, n);
		}
	}

	public void init() {
		this.lastrun = 0;
		this.jobs = new LinkedList();
		this.invmap = new ArrayList(50);
		for (int r = 0; r < NROWS; r++)
			for (int cb = 0; cb < NCABS; cb++)
				for (int cg = 0; cg < NCAGES; cg++)
					for (int m = 0; m < NMODS; m++)
						for (int n = 0; n < NNODES; n++)
							this.nodes[r][cb][cg][m][n] = new Node(-1);
		this.width = Integer.parseInt(this.getParameter("width"));
		this.height = Integer.parseInt(this.getParameter("height"));
		this.node_url = this.getParameter("node_url");
		this.job_url = this.getParameter("job_url");
		this.resize(this.width, this.height);

		this.load(DT_JOB);
		this.load(DT_NODE);
	}

	public synchronized void update(Graphics g) {
		Date now = new Date();
		if (this.lastrun + SLEEPINTV >= now.getTime())
			return;
		try {
			this.load(DT_JOB);
			this.load(DT_NODE);
			this.lastrun = now.getTime();
			this.paint(g);
		} catch (Exception e) {
			System.err.println(e.toString());
		}
	}

	public Job job_get(LinkedList jobs, String sjid) {
		int id = Integer.parseInt(sjid);
		Job j;

		for (Iterator it = jobs.iterator();
		  it.hasNext() && (j = (Job)it.next()) != null; )
			if (j.id == id)
				return (j);
		j = new Job(id);
		jobs.addFirst(j);
		return (j);
	}

	/*
	 * Format:
	 * jobid	owner		tmd tmu memkb	nc	queue	name
	 * ===================================================================
	 * 34892	mkurniko	300	187	9900	4	batch	lang-heat-450k.job
	 */
	public void load_job(BufferedReader r) throws Exception {
		LinkedList newj = new LinkedList();
		int njobs = 0;
		Node n;
		Job j;
		String l;
		int lineno = 0;

		while ((l = r.readLine()) != null) {
			lineno++;
			l = l.replaceFirst("^\\s+", "");
			if (l.equals("") || l.charAt(0) == '#')
				continue;

			String[] s = l.split("\\s+");
			if (s.length != 8) {
				System.err.println("[job] malformed line " +
				  "(job:" + lineno + "): " + l);
				continue;
			}
			j = job_get(newj, s[0]);
			j.owner = s[1];
			njobs++;
		}

		this.jobs = newj;
		this.njobs = njobs;
		int k = 0;
		for (Iterator it = jobs.iterator();
		  it.hasNext() && (j = (Job)it.next()) != null; k++)
			j.color = getColor(k);
	}

	public synchronized void paint(Graphics g) {
		Node node;

		g.setColor(new Color(0xcc, 0xcc, 0xcc));
		g.fillRect(0, 0, this.width, this.height);

		int x = 0, y = 0;
		int r, cb, cg, m, n;

		int legendheight = this.height / 4;

		int rowspace = 10;
		int rowheight = (this.height - legendheight - 1 -
		    (NROWS - 1) * rowspace) / NROWS;
		int cgspace = 3;
		int cgheight = (rowheight - (NCAGES - 1) * cgspace) / NCAGES;
		int cbspace = 3;
		int cbwidth = (this.width - 1 - (NCABS - 1) * cbspace) / NCABS;
		int modwidth = cbwidth / NMODS;
		int nodeheight = cgheight / NNODES;
		int nodewidth = modwidth;

		for (r = 0; r < NROWS; r++, y += rowheight + rowspace) {
			for (cb = 0; cb < NCABS; cb++, x += cbwidth + cbspace) {
				for (cg = NCAGES - 1; cg >= 0; cg--, y += cgheight + cgspace) {
					for (m = 0; m < NMODS; m++, x += modwidth) {
						for (n = 0; n < NNODES; n++, y += nodeheight) {
							node = this.nodes[r][cb][cg][m][n];
							Color c = null;
							if (node.state == ST_USED)
								c = node.job.color;
							else
								c = this.states[node.state].color;
							g.setColor(c);
							g.fillRect(x, y, nodewidth, nodeheight);
							g.setColor(Color.black);
							g.drawRect(x, y, nodewidth, nodeheight);
						}
						y -= nodeheight * NNODES;
					}
					x -= modwidth * NMODS;
				}
				y -= (cgheight + cgspace) * NCAGES;
			}
			x -= (cbwidth + cbspace) * NCABS;
		}

		y = this.height - legendheight + 10;
		g.setColor(Color.black);
		g.drawRect(0, y, this.width - 1, this.height - y - 1);

		int txheight = g.getFontMetrics().getHeight();
		int nst = this.states.length - 1;		/* Ignore ST_USED. */
		int boxspace = 2;
		int boxheight = txheight;
		int boxwidth = 10;

		g.drawString("- Legend -", this.width / 2 -
		    g.getFontMetrics().stringWidth("- Legend -") / 2,
		    y + txheight - 3);

		x = 3;
		y += txheight;
		for (int k = 0; k < this.states.length; k++, y += boxheight + boxspace) {
			if (this.states[k].color == null) {
				y -= boxheight + boxspace;
				continue;
			}
			g.setColor(this.states[k].color);
			g.fillRect(x, y, boxwidth, boxheight);
			g.setColor(Color.black);
			g.drawRect(x, y, boxwidth, boxheight);
			g.drawString(this.states[k].label +
			  " (" + this.states[k].nmemb + ")",
			  x + boxwidth + 4, y + txheight - 2);
		}
		Job j;
		Iterator it = this.jobs.iterator();
		for (int k = 1;
		    (it.hasNext() && (j = (Job)it.next()) != null);
		    k++, y += boxheight + boxspace) {
			if (y + txheight >= this.height) {
				/* Move to next column. */
				y = this.height - legendheight + 10 + txheight;
				x += this.width / 4;
			}
			g.setColor(j.color);
			g.fillRect(x, y, boxwidth, boxheight);
			g.setColor(Color.black);
			g.drawRect(x, y, boxwidth, boxheight);
			g.drawString(j.owner + "/" + j.id +
			  " (" + j.nmemb + ")", x + boxwidth + 4,
			  y + txheight - 2);
		}
	}

	public double[] hsv_to_rgb(double h, double s, double v) {
		double f, p, q, t;
		double r, g, b;
		int i;

		if (s == 0.0)
			return new double[] { v, v, v };
		if (h == 360.0)
			h = 0.0;
		h /= 60;
        i = (int)h;
        f = h - i;
        p = v * (1 - s);
        q = v * (1 - (s * f));
        t = v * (1 - (s * (1 - f)));

        switch (i) {
        case 0:  r = v;  g = t;  b = p;  break;
        case 1:  r = q;  g = v;  b = p;  break;
        case 2:  r = p;  g = v;  b = t;  break;
        case 3:  r = p;  g = q;  b = v;  break;
        case 4:  r = t;  g = p;  b = v;  break;
        case 5:  r = v;  g = p;  b = q;  break;
		default: r = 0;  g = 0;  b = 0;  break;
        }
		return (new double[] { r, g, b });
	}

	private static final double HUE_MIN = 0;
	private static final double HUE_MAX = 360;
	private static final double SAT_MIN = 0.3;
	private static final double SAT_MAX = 1.0;
	private static final double VAL_MIN = 0.5;
	private static final double VAL_MAX = 1.0;

	public Color getColor(int j) {
		int n = this.njobs;

		double hinc = 360.0 / n;
		double sinc = (SAT_MAX - SAT_MIN) / n;
		double vinc = (VAL_MAX - VAL_MIN) / n;

		double h = hinc * j + HUE_MIN;
		double s = sinc * j + SAT_MIN;
		double v = vinc * j + VAL_MIN;

		double[] rgb = hsv_to_rgb(h, s, v);

		return (new Color((int)(rgb[0] * 255),
		    (int)(rgb[1] * 255), (int)(rgb[2] * 255)));
	}
};

class Job {
	public int id;
	public String owner;
	public Color color;
	public int nmemb;

	public Job(int id) {
		this.id = id;
		this.nmemb = 0;
	}
};

class State {
	public Color color;
	public String label;
	public int nmemb;

	public State(String l, Color c) {
		this.color = c;
		this.label = l;
		this.nmemb = 0;
	}
};

class Node {
	public int nid;
	public Job job;
	public int state;

	public Node(int id) {
		this.nid = id;
	}
};

/* vim:set ts=4: */
