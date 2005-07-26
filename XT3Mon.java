/* $Id$ */

import java.applet.*;
import java.awt.*;
import java.io.*;
import java.util.*;

public class XT3Mon extends Applet implements Runnable {
	private final static int SLEEPINTV = 5000;

	private final static int NROWS = 2;
	private final static int NCABS = 11;
	private final static int NCAGES = 3;
	private final static int NMODS = 8;
	private final static int NNODES = 4;

	/*
	 * Login node IDs are appended to the end of the filename.
	 */
	private final static String _PATH_JOBMAP = "/home/torque/nids_list_phantom";
	private final static String _PATH_BADMAP = "/home/torque/bad_nids_list_login";
	private final static String _PATH_CHECKMAP = "/usr/users/torque/check_nids_list_login";
	private final static String _PATH_PHYSMAP = "/home/torque/ssconfig_phantom";

	private State[] states = new State[] {
		new State("Free", Color.white),			/* ST_FREE */
		new State("Disabled (PBS)", Color.red),	/* ST_DOWN */
		new State("Disabled (HW)", Color.gray),	/* ST_DISABLED */
		new State("Used", null),				/* ST_USED */
		new State("I/O", Color.yellow),			/* ST_IO */
		new State("Unaccounted", Color.blue),	/* ST_UNACC */
		new State("Bad", Color.pink),			/* ST_BAD */
		new State("Checking", Color.green)		/* ST_CHECK */
	};

	public final static int ST_FREE = 0;
	public final static int ST_DOWN = 1;
	public final static int ST_DISABLED = 2;
	public final static int ST_USED = 3;
	public final static int ST_IO = 4;
	public final static int ST_UNACC = 5;
	public final static int ST_BAD = 6;
	public final static int ST_CHECK = 7;

	private long lastrun;
	private Thread t;
	private int width;
	private int height;
	private Node[][][][][] nodes = new Node[NROWS][NCABS][NCAGES][NMODS][NNODES];
	private ArrayList invmap;
	private LinkedList jobs;
	private int njobs;
	private Color[] ccache;

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

	/*
	 * Coordinate format:
	 *	coord
	 *	============================================
	 *	c0-0c0s0n0
	 *
	 *	'C' cab# '-' row# 'c' cage# 's' slot# 'n' node#
	 */
	public int[] parse_coord(String coord) {
		int[] pos = { NROWS, NCABS, NCAGES, NMODS, NNODES };
		int val;

		int p = 0, j;
		for (int i = 0; i < coord.length(); i++) {
			char c = coord.charAt(i);
			if (Character.isDigit(c)) {
				if (p >= pos.length)
					return (null);
				j = i;
				while (++j < coord.length() &&
				  Character.isDigit(coord.charAt(j)))
					;
				val = Integer.parseInt(coord.substring(i, j));
				if (val >= pos[p])
					return (null);
				pos[p++] = val;
				i = j - 1;
			} else {
				switch (c) {
				case 'C': case 'c':
				case 'S': case 's':
				case 'n': case '-':
					break;
				default:
					return (null);
				}
			}
		}
		if (p != pos.length)
			return (null);
		return (pos);
	}

	/*
	 * Format is:
	 *	0	1		2
	 * 	nid	coord		x,y,z
	 *	=============================
	 *	2783	c1-10c0s0n1
	 */
	public void load_physmap() {
		int[] c;
		String fn = _PATH_PHYSMAP;
		try {
			BufferedReader r = new BufferedReader(new FileReader(fn));
			String l;
			int lineno = 0;
			while ((l = r.readLine()) != null) {
				lineno++;
				if (l.charAt(0) == '#')
					continue;
				String[] s = l.split(" ");
				if (s.length != 3 ||
				    (c = this.parse_coord(s[1])) == null) {
					System.err.println("[physmap] malformed line " +
					  "(" + fn + ":" + lineno + "): " + l);
					continue;
				}
				/*         r     cb    cg    m     n */
				Node n = this.nodes[c[0]][c[1]][c[2]][c[3]][c[4]];
				n.nid = Integer.parseInt(s[0]);
/*
				switch (s[3].charAt(0)) {
				case 'n':
					n.state = ST_DISABLED;
					break;
				case 'i':
					n.state = ST_IO;
					break;
				case 'c':
					// A white lie, but good enough.
					n.state = ST_FREE;
					break;
				}
*/
				n.state = ST_FREE;
				this.invmap.ensureCapacity(n.nid + 1);
				for (int k = this.invmap.size(); k <= n.nid; k++)
					this.invmap.add(null);
				this.invmap.set(n.nid, n);
			}
			r.close();
		} catch (FileNotFoundException e) {
			System.err.println("[physmap] " + e);
		} catch (IOException e) {
			/* This shouldn't happen... */
			System.err.println("[physmap] " + e);
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
		try {
			this.load_physmap();
		} catch (Exception e) {
			System.err.println("cannot open physical map: " + e);
			System.exit(1);
		}
		this.width = Integer.parseInt(this.getParameter("width"));
		this.height = Integer.parseInt(this.getParameter("height"));
		this.resize(this.width, this.height);
	}

	public synchronized void update(Graphics g) {
		Date now = new Date();
		if (this.lastrun + SLEEPINTV >= now.getTime())
			return;
		try {
			this.load_jobmap();
			this.lastrun = now.getTime();
			this.paint(g);
		} catch (Exception e) {
			System.err.println(e.toString());
		}
	}

	public int[] posForId(int id) {
		int[] pos = new int[5];
		pos[0] = id / NROWS;
		id %= NROWS;
		pos[1] = id / NCABS;
		id %= NCABS;
		pos[2] = id / NCAGES;
		id %= NCAGES;
		pos[3] = id / NMODS;
		id %= NMODS;
		pos[4] = id / NNODES;
		return (pos);
	}

	/*
	 * Format:
	 *      nid status      jobid
	 *      ================
	 *      1       1               0
	 *
	 * `status' is 0 or 1 for disabled or enabled.
	 * `jobid' is 0 if unassigned.
	 */
	public void load_jobmap() {
		LinkedList newj = new LinkedList();
		int nid, jobid, enabled, index = 0;
		Job j;
		Node n;

		try {
			String fn = _PATH_JOBMAP;
			BufferedReader r = new BufferedReader(new FileReader(fn));
			String l;
			int lineno = 0;
			while ((l = r.readLine()) != null) {
				lineno++;
				if (l.charAt(0) == '#')
					continue;
				String[] s = l.split(" ");
				if (s.length < 3) {
					System.err.println("[jobmap] malformed line " +
					  "(" + fn + ":" + lineno + "): " + l);
					continue;
				}
				nid = Integer.parseInt(s[0]);
				enabled = Integer.parseInt(s[1]);
				if (nid >= this.invmap.size() ||
				  (n = (Node)this.invmap.get(nid)) == null) {
					if (enabled == 0)
						continue;
					else
						throw new IOException("[jobmap " + fn + "] " +
						  "inconsistency: nid " + nid +
						  " should be disabled");
				}
				jobid = Integer.parseInt(s[2]);

				if (enabled == 0)
					n.state = ST_DOWN;
				else if (jobid == 0)
					n.state = ST_FREE;
				else {
					n.state = ST_USED;
					j = null;
					boolean found = false;
					for (Iterator it = newj.iterator();
					  it.hasNext() && (j = (Job)it.next()) != null; )
						if (j.id == jobid) {
							found = true;
							break;
						}
					if (!found) {
						j = new Job(jobid, ++index);
						newj.addFirst(j);
					}
					n.job = j;
				}
			}
			r.close();

		} catch (FileNotFoundException e) {
			System.err.println("[jobmap] " + e);
		} catch (IOException e) {
			System.err.println("[jobmap] " + e);
		}

		try {
			String fn = _PATH_BADMAP;
			BufferedReader r = new BufferedReader(new FileReader(fn));
			String l;
			int lineno = 0;
			while ((l = r.readLine()) != null) {
				lineno++;
				if (l.charAt(0) == '#')
					continue;
				String[] s = l.split(" ");
				if (s.length != 2) {
					System.err.println("[badmap] malformed line " +
					  "(" + fn + ":" + lineno + "): " + l);
					continue;
				}
				nid = Integer.parseInt(s[0]);
				enabled = Integer.parseInt(s[1]);
				if (nid >= this.invmap.size() ||
				  (n = (Node)this.invmap.get(nid)) == null) {
					if (enabled == 0)
						continue;
					else
						throw new IOException("[badmap " + fn + "] " +
						  "inconsistency: nid " + nid + " should be zero");
				}
				if (Integer.parseInt(s[1]) != 0)
					n.state = ST_BAD;
			}
		} catch (FileNotFoundException e) {
		} catch (IOException e) {
			System.err.println("[badmap] " + e);
		}

		try {
			String fn = _PATH_CHECKMAP;
			BufferedReader r = new BufferedReader(new FileReader(fn));
			String l;
			int lineno = 0;
			while ((l = r.readLine()) != null) {
				lineno++;
				if (l.charAt(0) == '#')
					continue;
				String[] s = l.split(" ");
				if (s.length != 2) {
					System.err.println("[checkmap] malformed line " +
					  "(" + fn + ":" + lineno + "): " + l);
					continue;
				}
				nid = Integer.parseInt(s[0]);
				enabled = Integer.parseInt(s[1]);
				if (nid >= this.invmap.size() ||
				  (n = (Node)this.invmap.get(nid)) == null) {
					if (enabled == 0)
						continue;
					else
						throw new IOException("[checkmap " + fn + "] " +
						  "inconsistency: nid " + nid + " should be zero");
				}
				if (Integer.parseInt(s[1]) != 0)
					n.state = ST_CHECK;

			}
		} catch (FileNotFoundException e) {
		} catch (IOException e) {
			System.err.println("[checkmap] " + e);
		}
		this.jobs = newj;
		this.njobs = index;
		this.ccache = new Color[njobs];
		for (int k = 0; k < njobs; k++)
			this.ccache[k] = null;
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
								c = getColor(node.job.index);
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
			g.drawString(this.states[k].label, x + boxwidth + 4, y + txheight - 2);
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
			g.setColor(this.getColor(k));
			g.fillRect(x, y, boxwidth, boxheight);
			g.setColor(Color.black);
			g.drawRect(x, y, boxwidth, boxheight);
			g.drawString("" + j.id, x + boxwidth + 4, y + txheight - 2);
		}
	}

	public Color getColor(int j) {
		if (this.ccache[j - 1] != null)
			return (this.ccache[j - 1]);

		int n = this.njobs;
		int r, g, b;
		double div = (double)j / n;

		r = (int)(Math.cos(div) * 255);
		g = (int)(Math.sin(div) * Math.sin(div)) * 255;
		b = (int)(Math.abs(Math.tan(div + Math.PI*3/4)) * 255);

		return (this.ccache[j - 1] = new Color(r, g, b));
	}
};

class Job {
	public int id;
	public int index;

	public Job(int id, int index) {
		this.id = id;
		this.index = index;
	}
};

class State {
	public Color color;
	public String label;

	public State(String l, Color c) {
		this.color = c;
		this.label = l;
	}
};

class Node {
	public int nid;
	public Job job;
	public int state;

	public Node(int id) {
		this.nid = id;
		this.state = XT3Mon.ST_UNACC;
	}
};

/* vim:set ts=4: */
