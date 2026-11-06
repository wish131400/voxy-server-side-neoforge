"""Compile actual queue sources and their synchronous replay baseline; no Minecraft/GPU."""
import json,os,pathlib,subprocess,tempfile
root=pathlib.Path(__file__).resolve().parents[3]
source=pathlib.Path(__file__).resolve().parent
java_home=pathlib.Path(os.environ['JAVA_HOME'])/'bin'
with tempfile.TemporaryDirectory(prefix='vss-queue-replay-') as tmp:
 files=[source/n for n in ['LegacyPredictionVoxyDetailQueue.java','Stubs.java','Replay.java','JitterReplay.java']]
 files.append(root/'src/main/java/dev/xantha/vss/client/prediction/PredictionVoxyDetailQueue.java')
 subprocess.run([str(java_home/'javac'),'-d',tmp,*map(str,files)],check=True)
 for radius,name in [(128,'Replay'),(8,'JitterReplay')]:
  result=json.loads(subprocess.check_output([str(java_home/'java'),'-cp',tmp,'dev.xantha.vss.client.prediction.'+name],text=True))
  result['cameraRadiusBlocks']=radius;result['synchronousReplayOnly']=True
  print(json.dumps(result))
