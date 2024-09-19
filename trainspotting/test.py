import time
import subprocess

def run_experiment(speed1, speed2, duration):
    command = f"java -cp bin Main Lab1.map {speed1} {speed2}"
    try:
        process = subprocess.Popen(command, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        stdout, stderr = process.communicate(timeout=duration)
        return_code = process.returncode
        stdout = stdout.decode('utf-8', errors='ignore')
        stderr = stderr.decode('utf-8', errors='ignore')
        if return_code != 0:
            print("Java program exited with error code:", return_code)
            print("Error message:")
            print(stderr)
    except subprocess.TimeoutExpired:
        process.kill()
    except Exception as e:
        print(e)

def main():
    speeds1 = range(20, 0, -1)
    speeds2 = range(0, 20, 1)
    results = []

    for speed1 in speeds1:
        for speed2 in speeds2:
            if speed1 > 10 and speed2 > 10:
                duration = 100
            else:
                duration = 400
            print("Testing with speeds: Train1={}, Train2={}".format(speed1, speed2))
            start_time = time.time()
            run_experiment(speed1, speed2, duration)
            end_time = time.time()

            print("Duration: {:.2f} seconds".format(end_time - start_time))
            print("---")

if __name__ == "__main__":
    main()