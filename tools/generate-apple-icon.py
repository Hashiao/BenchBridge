"""将项目现有芯片标记绘制为 Apple 应用图标。 / Render the existing chip mark as an Apple app icon."""
from pathlib import Path
import json
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[1]/'ios/BenchBridge/Assets.xcassets'
icon=ROOT/'AppIcon.appiconset';icon.mkdir(parents=True,exist_ok=True)
image=Image.new('RGB',(1024,1024),(80,77,202));draw=ImageDraw.Draw(image)
def line(points,width):draw.line(points,fill='white',width=width,joint='curve')
draw.rounded_rectangle((260,260,764,764),radius=32,outline='white',width=64)
draw.rounded_rectangle((395,395,629,629),radius=12,outline='white',width=48)
for coordinate in (390,634):
    line([(coordinate,140),(coordinate,260)],58);line([(coordinate,764),(coordinate,884)],58)
    line([(140,coordinate),(260,coordinate)],58);line([(764,coordinate),(884,coordinate)],58)
image.save(icon/'AppIcon.png')
(icon/'Contents.json').write_text(json.dumps({'images':[{'filename':'AppIcon.png','idiom':'universal','platform':'ios','size':'1024x1024'}],'info':{'author':'xcode','version':1}},indent=2)+'\n')
(ROOT/'Contents.json').write_text(json.dumps({'info':{'author':'xcode','version':1}},indent=2)+'\n')
