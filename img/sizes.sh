for f in ic_logo.svg; do
	echo $f-export-
	inkscape -z $f -w 48 --export-type=PNG --export-filename=../pretixscan/app/src/main/res/drawable-mdpi/$(echo $f | sed s/svg/png/);
	inkscape -z $f -w 72 --export-type=PNG --export-filename=../pretixscan/app/src/main/res/drawable-hdpi/$(echo $f | sed s/svg/png/);
	inkscape -z $f -w 96 --export-type=PNG --export-filename=../pretixscan/app/src/main/res/drawable-xhdpi/$(echo $f | sed s/svg/png/);
	inkscape -z $f -w 144 --export-type=PNG --export-filename=../pretixscan/app/src/main/res/drawable-xxhdpi/$(echo $f | sed s/svg/png/);
	inkscape -z $f -w 192 --export-type=PNG --export-filename=../pretixscan/app/src/main/res/drawable-xxxhdpi/$(echo $f | sed s/svg/png/);
done;
