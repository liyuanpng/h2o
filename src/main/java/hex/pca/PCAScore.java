package hex.pca;

import hex.FrameTask;
import hex.FrameTask.DataInfo;
import water.Job;
import water.Job.FrameJob;
import water.Key;
import water.api.DocGen;
import water.fvec.Frame;
import water.fvec.NewChunk;
import water.fvec.Vec;
import water.util.RString;

/**
 * Principal Components Scoring
 * This algorithm maps a dataset into the subspace generated by the principal components.
 * If A = dataset to be scored, and B = eigenvector matrix (rows = features, cols = components),
 * then the score is simply A * B, assuming the column features match up exactly.
 * <a href = "http://en.wikipedia.org/wiki/Principal_component_analysis">PCA on Wikipedia</a>
 * @author anqi_fu
 *
 */
public class PCAScore extends FrameJob {
  static final int API_WEAVER = 1;
  static public DocGen.FieldDoc[] DOC_FIELDS;
  static final String DOC_GET = "pca_score";

  @API(help = "PCA model to use for scoring", required = true, filter = Default.class)
  PCAModel model;

  @API(help = "Number of principal components to return", filter = Default.class, lmin = 1, lmax = 5000)
  int num_pc = 1;

  @Override protected void execImpl() {
    // Note: Source data MUST contain all features (matched by name) used to build PCA model!
    // If additional columns exist in source, they are automatically ignored in scoring
    new Frame(destination_key, new String[0], new Vec[0]).delete_and_lock(self());
    Frame fr = model.adapt(source, true)[0];
    int nfeat = model._names.length;
    DataInfo dinfo = new DataInfo(fr, 0, false, model.normSub, model.normMul, DataInfo.TransformType.STANDARDIZE, null, null);

    PCAScoreTask tsk = new PCAScoreTask(this, dinfo, nfeat, num_pc, model.eigVec);
    tsk.doAll(num_pc, dinfo._adaptedFrame);
    String[] names = new String[num_pc];
    String[][] domains = new String[num_pc][];
    for(int i = 0; i < num_pc; i++) {
      names[i] = "PC" + i;
      domains[i] = null;
    }
    tsk.outputFrame(destination_key, names, domains).unlock(self());
  }

  @Override protected void init() {
    super.init();
    if(model != null && num_pc > model.num_pc)
      throw new IllegalArgumentException("Argument 'num_pc' must be between 1 and " + model.num_pc);
  }

  /* @Override public float progress() {
    ChunkProgress progress = UKV.get(progressKey());
    return (progress != null ? progress.progress() : 0);
  } */

  public static String link(Key modelKey, String content) {
    return link("model", modelKey, content);
  }

  public static String link(String key_param, Key k, String content) {
    RString rs = new RString("<a href='/2/PCAScore.query?%key_param=%$key'>%content</a>");
    rs.replace("key_param", key_param);
    rs.replace("key", k.toString());
    rs.replace("content", content);
    return rs.toString();
  }

  // Matrix multiplication A * B, where A is a skinny matrix (# rows >> # cols) and B is a
  // small matrix that fits on a single node. For PCA scoring, the cols of A (rows of B) are
  // the features of the input dataset, while the cols of B are the principal components.
  public static class PCAScoreTask extends FrameTask<PCAScoreTask> {
    final int _nfeat;         // number of features
    final int _ncomp;         // number of principal components (<= nfeat)
    final double[][] _eigvec; // eigenvector matrix

    public PCAScoreTask(Job job, DataInfo dinfo, int nfeat, int ncomp, double[][] eigvec) {
      super(job.self(), dinfo);
      _nfeat = nfeat;
      _ncomp = ncomp;
      _eigvec = eigvec;
    }

    // Note: Rows with NAs (missing values) are automatically skipped!
    @Override protected void processRow(long gid, double[] nums, int ncats, int[] cats, double[] response, NewChunk[] outputs) {
      for(int c = 0; c < _ncomp; c++) {
        double x = 0;
        for(int d = 0; d < ncats; d++)
          x += _eigvec[cats[d]][c];
        int k = _dinfo.numStart();
        for(int d = 0; d < nums.length; d++)
          x += nums[d]*_eigvec[k++][c];
        assert k == _eigvec.length;
        outputs[c].addNum(x);
      }
    }
  }
}
