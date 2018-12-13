mkdir -p ../pretixdroid/pretixdroid/app/res/drawable-ldpi/
mkdir -p ../pretixdroid/app/src/main/res/drawable-mdpi/
mkdir -p ../pretixdroid/app/src/main/res/drawable-hdpi/
mkdir -p ../pretixdroid/app/src/main/res/drawable-xhdpi/
mkdir -p ../pretixdroid/app/src/main/res/drawable-xxhdpi/
mkdir -p ../pretixdroid/app/src/main/res/drawable-xxxhdpi/

for f in ic_*.svg; do
	echo $f;
	inkscape -zf $f -w 36 -e ../pretixdroid/app/res/drawable-ldpi/$(echo $f | sed s/svg/png/);
	inkscape -zf $f -w 48 -e ../pretixdroid/app/src/main/res/drawable-mdpi/$(echo $f | sed s/svg/png/);
	inkscape -zf $f -w 72 -e ../pretixdroid/app/src/main/res/drawable-hdpi/$(echo $f | sed s/svg/png/);
	inkscape -zf $f -w 96 -e ../pretixdroid/app/src/main/res/drawable-xhdpi/$(echo $f | sed s/svg/png/);
	inkscape -zf $f -w 144 -e ../pretixdroid/app/src/main/res/drawable-xxhdpi/$(echo $f | sed s/svg/png/);
	inkscape -zf $f -w 192 -e ../pretixdroid/app/src/main/res/drawable-xxxhdpi/$(echo $f | sed s/svg/png/);
done;

for f in logo*.svg; do
	echo $f;
	inkscape -zf $f -w 225 -e ../pretixdroid/app/src/main/res/drawable-ldpi/$(echo $f | sed s/svg/png/);
	inkscape -zf $f -w 300 -e ../pretixdroid/app/src/main/res/drawable-mdpi/$(echo $f | sed s/svg/png/);
	inkscape -zf $f -w 450 -e ../pretixdroid/app/src/main/res/drawable-hdpi/$(echo $f | sed s/svg/png/);
	inkscape -zf $f -w 600 -e ../pretixdroid/app/src/main/res/drawable-xhdpi/$(echo $f | sed s/svg/png/);
	inkscape -zf $f -w 900 -e ../pretixdroid/app/src/main/res/drawable-xxhdpi/$(echo $f | sed s/svg/png/);
	inkscape -zf $f -w 1200 -e ../pretixdroid/app/src/main/res/drawable-xxxhdpi/$(echo $f | sed s/svg/png/);
done;

